package com.bernardooechsler.chirp.infra.database.repositories

import com.bernardooechsler.chirp.domain.type.UserId
import com.bernardooechsler.chirp.infra.database.entities.UserEntity
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository: JpaRepository<UserEntity, UserId> {

    fun findByEmail(email: String): UserEntity?
    fun findByEmailOrUsername(email: String, username: String): UserEntity?
}