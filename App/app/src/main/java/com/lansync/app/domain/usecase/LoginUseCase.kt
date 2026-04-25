package com.lansync.app.domain.usecase

import com.lansync.app.data.repository.SyncRepository
import com.lansync.app.domain.model.LoginResponse
import javax.inject.Inject

class LoginUseCase @Inject constructor(
    private val repository: SyncRepository
) {
    suspend operator fun invoke(username: String, password: String): Result<LoginResponse> {
        if (username.isBlank()) {
            return Result.failure(Exception("用户名不能为空"))
        }
        if (password.isBlank()) {
            return Result.failure(Exception("密码不能为空"))
        }
        return repository.login(username, password)
    }
}
