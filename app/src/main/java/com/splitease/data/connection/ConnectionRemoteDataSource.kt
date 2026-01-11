package com.splitease.data.connection

import java.io.IOException
import javax.inject.Inject

/**
 * Remote data source for connection operations.
 * Abstracts Retrofit from the Manager layer.
 */
interface ConnectionRemoteDataSource {
    /**
     * Creates a user-level invite.
     * @throws IOException on network or parsing failure
     */
    suspend fun createUserInvite(): CreateUserInviteResponse
}

class ConnectionRemoteDataSourceImpl @Inject constructor(
    private val api: ConnectionApiService
) : ConnectionRemoteDataSource {

    override suspend fun createUserInvite(): CreateUserInviteResponse {
        val response = api.createUserInvite()
        val body = response.body()
        if (response.isSuccessful && body != null) return body
        throw IOException("Invite creation failed")
    }
}
