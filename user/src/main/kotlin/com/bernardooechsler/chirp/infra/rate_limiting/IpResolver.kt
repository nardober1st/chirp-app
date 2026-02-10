package com.bernardooechsler.chirp.infra.rate_limiting

import com.bernardooechsler.chirp.infra.config.NginxConfig
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.stereotype.Component
import java.net.Inet4Address
import java.net.Inet6Address

@Component  // Spring-managed bean, injectable wherever needed
class IpResolver(
    private val nginxConfig: NginxConfig  // Injected configuration from application.yml
) {
    companion object {
        // Private/internal IP ranges that should never be treated as "real" client IPs
        // These are reserved for internal networks (RFC 1918 for IPv4, RFC 4193 for IPv6)
        // If someone claims their IP is in these ranges via headers, it's likely spoofed or misconfigured
        private val PRIVATE_IP_RANGES = listOf(
            "10.0.0.0/8",       // Class A private network (10.x.x.x)
            "172.16.0.0/12",    // Class B private network (172.16.x.x - 172.31.x.x)
            "192.168.0.0/16",   // Class C private network (192.168.x.x)
            "127.0.0.0/8",      // Loopback (localhost)
            "::1/128",          // IPv6 loopback
            "fc00::/7",         // IPv6 unique local addresses
            "fe80::/10"         // IPv6 link-local addresses
        ).map { IpAddressMatcher(it) }  // Convert to matchers for easy checking

        // Known invalid/placeholder values that some proxies or clients might send
        private val INVALID_IPS = listOf(
            "unknown",      // Some proxies send this literal string
            "unavailable",  // Another placeholder value
            "0.0.0.0",      // Invalid "any" address
            "::"            // IPv6 "any" address
        )
    }

    // Logger for security warnings and debugging
    private val logger = LoggerFactory.getLogger(IpResolver::class.java)

    // Pre-compute matchers for trusted proxy IPs from configuration
    // This runs once at startup, not on every request (performance optimization)
    private val trustedMatchers: List<IpAddressMatcher> = nginxConfig
        .trustedIps
        .filter { it.isNotBlank() }  // Skip empty entries
        .map { proxy ->
            // Normalize to CIDR notation for consistent matching
            // CIDR = "Classless Inter-Domain Routing" (e.g., 10.0.0.5/32 means exactly that one IP)
            val cidr = when {
                proxy.contains("/") -> proxy                    // Already CIDR format
                proxy.contains(":") -> "$proxy/128"             // IPv6 single host
                else -> "$proxy/32"                             // IPv4 single host
            }
            IpAddressMatcher(cidr)
        }

    // Main entry point: extracts the real client IP from a request
    // Handles both direct connections and proxied connections securely
    fun getClientIp(request: HttpServletRequest): String {
        // The IP address of whoever directly connected to our server
        // This is either the real client (direct connection) or the proxy (proxied connection)
        val remoteAddr = request.remoteAddr

        // Check if the direct connection came from one of our trusted proxies
        if (!isFromTrustedProxy(remoteAddr)) {
            // NOT from a trusted proxy - this is either:
            // 1. A direct connection from a real user (fine in dev, bad in prod)
            // 2. Someone trying to bypass the proxy (security concern)

            if (nginxConfig.requireProxy) {
                // In production mode: reject direct connections entirely
                logger.warn("Direct connection attempt from $remoteAddr")
                throw SecurityException("No valid client IP in proxy headers")
            }

            // In dev mode: allow direct connections, use their IP directly
            return remoteAddr
        }

        // Request IS from a trusted proxy - now we can safely read the forwarded headers
        // The proxy puts the real client IP in X-Real-IP header
        val clientIp = extractFromXRealIp(request, remoteAddr)

        if (clientIp == null) {
            // Proxy connected but didn't send a valid client IP header
            // This is a misconfiguration or edge case
            logger.warn("No valid client IP in proxy headers")
            if (nginxConfig.requireProxy) {
                throw SecurityException("No valid client IP in proxy headers")
            }
        }

        // Return the extracted client IP, or fall back to proxy IP if extraction failed
        return clientIp ?: remoteAddr
    }

    // Extracts and validates the client IP from the X-Real-IP header
    // X-Real-IP is simpler than X-Forwarded-For (single IP, not a chain)
    private fun extractFromXRealIp(
        request: HttpServletRequest,
        proxyIp: String  // Passed for logging context
    ): String? {
        // getHeader returns null if header doesn't exist
        // ?.let only executes the block if header exists
        return request.getHeader("X-Real-IP")?.let { header ->
            validateAndNormalizeIp(header, "X-Real-IP", proxyIp)
        }
    }

    // Validates that an IP string is actually a valid IP address
    // Returns normalized IP or null if invalid
    private fun validateAndNormalizeIp(ip: String, headerName: String, proxyIp: String): String? {
        val trimmedIp = ip.trim()

        // Reject blank or known-invalid placeholder values
        if (trimmedIp.isBlank() || INVALID_IPS.contains(trimmedIp)) {
            logger.debug("Invalid IP in $headerName: $ip from proxy $proxyIp")
            return null
        }

        return try {
            // Parse the IP to validate format and normalize representation
            // This also handles edge cases like "192.168.001.001" -> "192.168.1.1"
            val inetAddr = when {
                trimmedIp.contains(":") -> Inet6Address.getByName(trimmedIp)  // IPv6
                trimmedIp.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) ->       // IPv4 pattern
                    Inet4Address.getByName(trimmedIp)
                else -> {
                    // Doesn't look like any valid IP format
                    logger.warn("Invalid IP format in $headerName: $trimmedIp from proxy $proxyIp")
                    return null
                }
            }

            // Log a warning if the "client IP" is actually a private address
            // This might indicate misconfiguration (e.g., nested proxies)
            if (isPrivateIp(inetAddr.hostAddress)) {
                logger.debug("Private IP in $headerName: $trimmedIp from proxy $proxyIp")
            }

            // Return the normalized IP string
            inetAddr.hostAddress
        } catch (e: Exception) {
            // InetAddress.getByName can throw if the IP is malformed
            logger.warn("Invalid IP format in $headerName: $trimmedIp from proxy $proxyIp", e)
            null
        }
    }

    // Checks if an IP falls within any private/reserved range
    private fun isPrivateIp(ip: String): Boolean {
        return PRIVATE_IP_RANGES.any { it.matches(ip) }
    }

    // Checks if an IP is in our configured list of trusted proxies
    private fun isFromTrustedProxy(ip: String): Boolean {
        return trustedMatchers.any { matcher ->
            matcher.matches(ip)
        }
    }
}
