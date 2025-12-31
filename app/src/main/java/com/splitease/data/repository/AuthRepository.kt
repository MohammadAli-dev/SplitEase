package com.splitease.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.User
import com.splitease.data.remote.LoginRequest
import com.splitease.data.remote.SignupRequest
import com.splitease.data.remote.SplitEaseApi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface AuthRepository {
    fun getCurrentUser(): Flow<User?>
    fun getCurrentUserId(): String?
    suspend fun login(email: String, password: String): Result<Unit>
    suspend fun signup(name: String, email: String, password: String): Result<Unit>
    suspend fun logout()
}

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val api: SplitEaseApi,
    private val userDao: UserDao,
    private val database: com.splitease.data.local.AppDatabase,
    private val sharedPreferences: SharedPreferences
) : AuthRepository {

    override fun getCurrentUser(): Flow<User?> {
        return userDao.getAnyUser()
    }

    override fun getCurrentUserId(): String? {
        return sharedPreferences.getString("current_user_id", null)
    }

    override suspend fun login(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.login(LoginRequest(email))
            saveUserAndToken(response)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun signup(name: String, email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.signup(SignupRequest(name, email))
            saveUserAndToken(response)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun logout() = withContext(Dispatchers.IO) {
        // Clear SharedPreferences
        sharedPreferences.edit().clear().apply()
        
        // Clear all Room tables
        database.clearAllTables()
    }

    private suspend fun saveUserAndToken(response: com.splitease.data.remote.AuthResponse) {
        // Save Token
        sharedPreferences.edit()
            .putString("auth_token", response.token)
            .putString("current_user_id", response.userId)
            .apply()

        // Save User to DB
        val user = User(
            id = response.userId,
            name = response.name,
            email = response.email,
            profileUrl = response.profileUrl
        )
        userDao.insertUser(user)
    }
}
