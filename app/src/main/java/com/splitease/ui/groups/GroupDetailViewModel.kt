package com.splitease.ui.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.Group
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface GroupDetailUiState {
    data object Loading : GroupDetailUiState
    data class Success(val group: Group, val expenses: List<Expense>) : GroupDetailUiState
    data class Error(val message: String) : GroupDetailUiState
}

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupDao: GroupDao,
    private val expenseDao: ExpenseDao
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId") ?: ""

    private val _retryTrigger = MutableStateFlow(0)

    val uiState: StateFlow<GroupDetailUiState> = combine(
        groupDao.getGroup(groupId),
        expenseDao.getExpensesForGroup(groupId),
        _retryTrigger
    ) { group, expenses, _ ->
        if (group == null) {
            GroupDetailUiState.Error("Group not found")
        } else {
            GroupDetailUiState.Success(group, expenses)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GroupDetailUiState.Loading
    )

    fun retry() {
        viewModelScope.launch {
            _retryTrigger.value++
        }
    }
}
