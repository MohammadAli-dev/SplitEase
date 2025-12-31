package com.splitease.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class DashboardUiState(
    val totalOwed: BigDecimal = BigDecimal.ZERO,
    val totalOwing: BigDecimal = BigDecimal.ZERO,
    val isLoading: Boolean = true
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadDashboardData()
    }

    private fun loadDashboardData() {
        viewModelScope.launch {
            val userId = authRepository.getCurrentUserId()
            if (userId == null) {
                _uiState.value = DashboardUiState(isLoading = false)
                return@launch
            }

            expenseDao.getSplitsForUser(userId).collectLatest { splits ->
                // Domain calculation: Sum positive (owed) and negative (owing) amounts
                // For now, simplified: all splits are amounts the user owes or is owed
                // In real scenario, we'd need to know if user is payer or participant
                // For Sprint 4 MVP, let's assume:
                // - Positive amounts = user is owed
                // - Negative amounts = user owes
                // This is a placeholder logic. Real logic would involve:
                // 1. Get all expenses
                // 2. For each expense, check if user is payer or participant
                // 3. Calculate net balance
                
                val totalOwed = splits
                    .filter { it.amount > BigDecimal.ZERO }
                    .sumOf { it.amount }
                
                val totalOwing = splits
                    .filter { it.amount < BigDecimal.ZERO }
                    .sumOf { it.amount.abs() }

                _uiState.value = DashboardUiState(
                    totalOwed = totalOwed,
                    totalOwing = totalOwing,
                    isLoading = false
                )
            }
        }
    }
}
