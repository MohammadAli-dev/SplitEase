package com.splitease.data.identity

import com.splitease.data.auth.TokenManager
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface UserContext {
    val userId: Flow<String>
    val isAuthenticated: Flow<Boolean>
}

@Singleton
class UserContextImpl @Inject constructor(
    private val localUserManager: LocalUserManager,
    private val tokenManager: TokenManager
) : UserContext {

    override val userId: Flow<String> = localUserManager.userId

    // Driven by TokenManager - true if valid (non-expired) token exists
    override val isAuthenticated: Flow<Boolean> = tokenManager.hasValidToken()
}
