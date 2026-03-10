package com.bernardooechsler.chirp.infra.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration  // Marks this as a Spring configuration class (provides beans)
class SupabaseRestClientConfig(
    // Inject values from application.yml at construction time
    @param:Value("\${supabase.url}") private val supabaseUrl: String,
    @param:Value("\${supabase.service-key}") private val supabaseServiceKey: String,
) {

    @Bean  // This method produces a Spring-managed bean
    fun supabaseRestClient(): RestClient {
        return RestClient.builder()
            .baseUrl(supabaseUrl)  // All requests will use this as the base
            .defaultHeader("Authorization", "Bearer $supabaseServiceKey")  // Auth on every request
            .build()
    }
}