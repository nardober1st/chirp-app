package com.bernardooechsler.chirp.service.auth

import com.bernardooechsler.chirp.domain.exception.UserAlreadyExistsException
import com.bernardooechsler.chirp.domain.model.User
import com.bernardooechsler.chirp.infra.database.entities.UserEntity
import com.bernardooechsler.chirp.infra.database.mappers.toUser
import com.bernardooechsler.chirp.infra.database.repositories.UserRepository
import com.bernardooechsler.chirp.infra.security.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {

    fun register(email: String, username: String, password: String): User {
        val user = userRepository.findByEmailOrUsername(
            email = email.trim(),
            username = username.trim()
        )
        if(user != null) {
            throw UserAlreadyExistsException()
        }

        val savedUser = userRepository.save(
            UserEntity(
                email = email.trim(),
                username = username.trim(),
                hashedPassword = passwordEncoder.encode(password)
            )
        ).toUser()

        return savedUser
    }
}