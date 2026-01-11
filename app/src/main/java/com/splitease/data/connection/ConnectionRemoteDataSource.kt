package com.splitease.data.connection

import java.io.IOException
import javax.inject.Inject

/**
 * Remote data source for connection operations.
 * Abstracts Retrofit from the Manager layer.
 */
interface ConnectionRemoteDataSource {
    /**
 * Creates a user invite on the remote service.
 *
 * @return The created `CreateUserInviteResponse` containing invite details.
 * @throws IOException if the network request fails or the response body cannot be parsed.
 */
    suspend fun createUserInvite(): CreateUserInviteResponse
}

class ConnectionRemoteDataSourceImpl @Inject constructor(
    private val api: ConnectionApiService
) : ConnectionRemoteDataSource {

    /**
     * Creates a user invite via the remote API and returns the parsed response.
     *
     * @return The created CreateUserInviteResponse.
     * @throws IOException if the HTTP request is unsuccessful or the response body is null.
     */
    override suspend fun createUserInvite(): CreateUserInviteResponse {
        val response = api.createUserInvite()
        val body = response.body()
        if (response.isSuccessful && body != null) return body
        throw IOException("Invite creation failed")
    }
}