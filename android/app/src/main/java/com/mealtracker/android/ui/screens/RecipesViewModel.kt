package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Recipe
import com.mealtracker.android.network.models.RecipeDetail
import com.mealtracker.android.network.models.RecipeUpdateRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val SEARCH_DEBOUNCE_MS = 350L

/**
 * Backs the new Recipes tab (see design discussion: replacing Meal Plan
 * -- pre-logging real meals directly into the Journal already served
 * that planning need, so the separate draft-then-commit staging area
 * wasn't earning its keep). Browse-only for ingredients (add/remove
 * ingredients still happens through the meal-logging-anchored
 * RecipeInfoScreen in MealDetailScreen.kt, reached by actually logging
 * a recipe) -- this screen's own editing is limited to metadata: name,
 * instructions, source URL, servings, and the hero image. Both
 * recipe_type values are listed together and both get the full
 * instructions/source-URL treatment, since they share the same
 * underlying table/column and a "meal" can be just as involved as a
 * recipe (see design discussion: pancakes saved as a meal still
 * benefit from having steps/a source).
 */
data class RecipesUiState(
    val isLoadingList: Boolean = true,
    val recipes: List<Recipe> = emptyList(),
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val listError: String? = null,

    val selectedRecipeId: Int? = null,
    val recipeDetail: RecipeDetail? = null,
    val isLoadingDetail: Boolean = false,
    val detailError: String? = null,

    val isEditing: Boolean = false,
    val editName: String = "",
    val editInstructions: String = "",
    val editSourceUrl: String = "",
    val editServings: String = "1",
    val isSaving: Boolean = false,
    val saveError: String? = null,

    val isUploadingImage: Boolean = false,
    val imageError: String? = null
)

class RecipesViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(RecipesUiState())
    val uiState: StateFlow<RecipesUiState> = _uiState

    init {
        loadList()
    }

    private var searchJob: Job? = null

    fun loadList() {
        _uiState.value = _uiState.value.copy(isLoadingList = true, listError = null)
        viewModelScope.launch {
            try {
                val recipes = ApiClient.service.searchRecipes(query = null, recipeType = null, limit = 200)
                _uiState.value = _uiState.value.copy(isLoadingList = false, recipes = recipes)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingList = false,
                    listError = e.message ?: "Couldn't load recipes"
                )
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            loadList()
            return
        }
        _uiState.value = _uiState.value.copy(isSearching = true)
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            try {
                val recipes = ApiClient.service.searchRecipes(query = query, recipeType = null, limit = 200)
                if (_uiState.value.searchQuery == query) {
                    _uiState.value = _uiState.value.copy(isSearching = false, recipes = recipes)
                }
            } catch (e: Exception) {
                if (_uiState.value.searchQuery == query) {
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        listError = e.message ?: "Couldn't search recipes"
                    )
                }
            }
        }
    }

    fun openRecipe(recipeId: Int) {
        _uiState.value = _uiState.value.copy(
            selectedRecipeId = recipeId,
            isLoadingDetail = true,
            detailError = null,
            isEditing = false
        )
        viewModelScope.launch {
            try {
                val detail = ApiClient.service.getRecipe(recipeId)
                _uiState.value = _uiState.value.copy(isLoadingDetail = false, recipeDetail = detail)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingDetail = false,
                    detailError = e.message ?: "Couldn't load that recipe"
                )
            }
        }
    }

    fun dismissDetail() {
        _uiState.value = _uiState.value.copy(selectedRecipeId = null, recipeDetail = null, detailError = null)
    }

    fun startEditing() {
        val detail = _uiState.value.recipeDetail ?: return
        _uiState.value = _uiState.value.copy(
            isEditing = true,
            editName = detail.name,
            editInstructions = detail.instructions ?: "",
            editSourceUrl = detail.sourceUrl ?: "",
            editServings = detail.servings,
            saveError = null
        )
    }

    fun cancelEditing() {
        _uiState.value = _uiState.value.copy(isEditing = false, saveError = null)
    }

    fun updateEditName(value: String) { _uiState.value = _uiState.value.copy(editName = value) }
    fun updateEditInstructions(value: String) { _uiState.value = _uiState.value.copy(editInstructions = value) }
    fun updateEditSourceUrl(value: String) { _uiState.value = _uiState.value.copy(editSourceUrl = value) }
    fun updateEditServings(value: String) { _uiState.value = _uiState.value.copy(editServings = value) }

    fun saveEdits() {
        val state = _uiState.value
        val recipeId = state.selectedRecipeId ?: return
        val detail = state.recipeDetail ?: return
        val name = state.editName.trim()
        if (name.isEmpty()) {
            _uiState.value = state.copy(saveError = "Name can't be empty")
            return
        }
        // Meals are always exactly 1 serving (not user-editable, same
        // convention as the meal-logging-anchored RecipeInfoScreen) --
        // only recipes get an editable servings count.
        val servings = if (detail.recipeType == "meal") null else state.editServings.toDoubleOrNull()

        _uiState.value = state.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.updateRecipe(
                    recipeId,
                    RecipeUpdateRequest(
                        name = name,
                        instructions = state.editInstructions.trim().ifEmpty { null },
                        sourceUrl = state.editSourceUrl.trim().ifEmpty { null },
                        servings = servings
                    )
                )
                _uiState.value = _uiState.value.copy(isSaving = false, isEditing = false, recipeDetail = updated)
                loadList()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, saveError = e.message ?: "Couldn't save")
            }
        }
    }

    fun deleteRecipe() {
        val recipeId = _uiState.value.selectedRecipeId ?: return
        viewModelScope.launch {
            try {
                ApiClient.service.deleteRecipe(recipeId)
                dismissDetail()
                loadList()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(detailError = e.message ?: "Couldn't delete")
            }
        }
    }

    fun updateRecipeImage(imagePath: String) {
        val recipeId = _uiState.value.selectedRecipeId ?: return
        _uiState.value = _uiState.value.copy(isUploadingImage = true, imageError = null)
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.updateRecipe(recipeId, RecipeUpdateRequest(imagePath = imagePath))
                _uiState.value = _uiState.value.copy(isUploadingImage = false, recipeDetail = updated)
                loadList()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploadingImage = false,
                    imageError = e.message ?: "Couldn't update photo"
                )
            }
        }
    }
}