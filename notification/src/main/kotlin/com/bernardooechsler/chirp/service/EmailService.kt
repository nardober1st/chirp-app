package com.bernardooechsler.chirp.service

import com.bernardooechsler.chirp.domain.type.UserId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.MailException

import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import java.time.Duration

// Responsible for constructing and sending transactional emails.
// Uses Spring's JavaMailSender abstraction, which connects to whatever
// SMTP provider is configured in application.yml (in this case, Mailgun).
// This lives in the notification module and gets called by the RabbitMQ
// event listener after consuming user events.
@Service
class EmailService(
    // Spring auto configures this bean from spring.mail.* properties.
    // It abstracts away the SMTP protocol details — you just create
    // a message and call send(). The underlying transport (Mailgun SMTP,
    // SendGrid, local Mailtrap, etc.) is purely a config concern.
    private val javaMailSender: JavaMailSender,
    // Delegates HTML rendering to a separate template service (likely Thymeleaf).
    // This keeps email construction (URLs, subjects) separate from
    // HTML rendering — single responsibility.
    private val templateService: EmailTemplateService,
    // The "from" address shown to recipients (e.g., "noreply@chirp.com").
    // Externalized to config so it can differ between environments.
    @param:Value("\${chirp.email.from}")
    private val emailFrom: String,
    // Base URL of the application (e.g., "https://chirp.com").
    // Used to construct clickable links in emails. Different per environment —
    // localhost:8080 in dev, your actual domain in production.
    @param:Value("\${chirp.email.url}")
    private val baseUrl: String,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun sendVerificationEmail(
        email: String,
        username: String,
        userId: UserId,
        token: String
    ) {
        logger.info("Sending verification email for user $userId")

        // Constructs the full verification URL that gets embedded in the email.
        // UriComponentsBuilder handles URL encoding automatically —
        // if the token contains special characters, they'll be properly escaped.
        // Result: "https://chirp.com/api/auth/verify?token=abc123"
        val verificationUrl = UriComponentsBuilder
            .fromUriString("$baseUrl/api/auth/verify")
            .queryParam("token", token)
            .build()
            .toUriString()

        // Renders the HTML email body using a Thymeleaf template.
        // The template file lives at something like
        // resources/templates/emails/account-verification.html
        // and uses the variables map to fill in dynamic content.
        val htmlContent = templateService.processTemplate(
            templateName = "emails/account-verification",
            variables = mapOf(
                "username" to username,
                "verificationUrl" to verificationUrl
            )
        )

        sendHtmlEmail(
            to = email,
            subject = "Verify your Chirp account",
            html = htmlContent
        )
    }

    fun sendPasswordResetEmail(
        email: String,
        username: String,
        userId: UserId,
        token: String,
        expiresIn: Duration
    ) {
        logger.info("Sending password reset email for user $userId")

        val resetPasswordUrl = UriComponentsBuilder
            .fromUriString("$baseUrl/api/auth/reset-password")
            .queryParam("token", token)
            .build()
            .toUriString()

        val htmlContent = templateService.processTemplate(
            templateName = "emails/reset-password",
            variables = mapOf(
                "username" to username,
                "resetPasswordUrl" to resetPasswordUrl,
                // Converts Duration to minutes for human-readable display
                // in the email body (e.g., "This link expires in 15 minutes").
                "expiresInMinutes" to expiresIn.toMinutes()
            )
        )

        sendHtmlEmail(
            to = email,
            subject = "Reset your Chirp password",
            html = htmlContent
        )
    }

    // Shared low-level method that both public methods delegate to.
    // Keeps MIME message construction in one place — DRY principle.
    private fun sendHtmlEmail(
        to: String,
        subject: String,
        html: String
    ) {
        // Creates a MIME message (the standard for HTML emails with
        // potential attachments, inline images, etc.)
        val message = javaMailSender.createMimeMessage()
        // MimeMessageHelper simplifies MIME message construction.
        // - multipart = true: enables mixed content (HTML + potential attachments)
        // - "UTF-8": ensures international characters render correctly
        MimeMessageHelper(message, true, "UTF-8").apply {
            setFrom(emailFrom)
            setTo(to)
            setSubject(subject)
            // The second parameter `true` tells Spring this is HTML content,
            // not plain text. Without it, the HTML tags would render as literals.
            setText(html, true)
        }

        try {
            javaMailSender.send(message)
        } catch(e: MailException) {
            logger.error("Could not send email", e)
            throw e  // add this line temporarily
        }
    }
}

// Catch-and-log rather than propagating the exception.
// This is intentional — since this runs from a RabbitMQ consumer,
// the retry logic is handled at the listener level (the exponential
// backoff config in application.yml). If this