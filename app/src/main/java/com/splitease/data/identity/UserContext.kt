package com.splitease.data.identity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

interface UserContext {
    val userId: Flow<String>
    val isAuthenticated: Flow<Boolean>
}

@Singleton
class UserContextImpl @Inject constructor(
    private val localUserManager: LocalUserManager
) : UserContext {

    override val userId: Flow<String> = localUserManager.userId

    // Hardcoded to false for Sprint 11A (Pre-Auth)
    override val isAuthenticated: Flow<Boolean> = flowOf(false)
}
