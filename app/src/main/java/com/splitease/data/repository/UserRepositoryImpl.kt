package com.splitease.data.repository

import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.User
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao
) : UserRepository {

    override fun getAllUsers(): Flow<List<User>> {
        return userDao.getAllUsers()
    }

    override fun getUser(userId: String): Flow<User?> {
        return userDao.getUser(userId)
    }

    override suspend fun createPhantomUser(name: String, email: String?, phone: String?): String {
        // Generate UUID internally - Repository is the authority on ID generation
        val newUserId = UUID.randomUUID().toString()
        
        // Create the user entity
        // Note: profileUrl is intentionally null for phantom users
        val newUser = User(
            id = newUserId,
            name = name,
            email = email?.trim()?.ifBlank { null },
            phone = phone?.trim()?.ifBlank { null },
            profileUrl = null
        )
        
        userDao.insertUser(newUser)
        return newUserId
    }
}
