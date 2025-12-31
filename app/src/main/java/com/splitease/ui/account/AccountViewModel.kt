package com.splitease.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.entities.User
import com.splitease.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    val currentUser: Flow<User?> = authRepository.getCurrentUser()

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}
