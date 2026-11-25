package com.nexa.app.core.backup

/**
 * Strength evaluation tier for custom backup passwords.
 */
enum class PasswordStrength {
    WEAK,
    FAIR,
    STRONG,
    VERY_STRONG
}

/**
 * Outcome of a backup password validation check.
 */
data class PasswordValidationResult(
    val isValid: Boolean,
    val strength: PasswordStrength,
    val errors: List<String> = emptyList()
) {
    val primaryErrorMessage: String?
        get() = errors.firstOrNull()
}

/**
 * Validates and evaluates custom backup passwords against security and usability standards.
 * Ensures zero plaintext leaks, enforces entropy standards, and validates confirmation match.
 */
object BackupPasswordValidator {

    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128
    const val RECOMMENDED_STRONG_LENGTH = 12
    const val RECOMMENDED_VERY_STRONG_LENGTH = 16

    /**
     * Validates a new backup password and its confirmation.
     */
    fun validate(password: String, confirmation: String): PasswordValidationResult {
        val errors = mutableListOf<String>()

        if (password.isBlank()) {
            errors.add("Password cannot be empty or contain only whitespace.")
            return PasswordValidationResult(isValid = false, strength = PasswordStrength.WEAK, errors = errors)
        }

        if (password.contains(" ") || password.any { it.isWhitespace() }) {
            errors.add("Password cannot contain spaces or whitespace characters.")
        }

        if (password.length < MIN_LENGTH) {
            errors.add("Password must be at least $MIN_LENGTH characters long.")
        }

        if (password.length > MAX_LENGTH) {
            errors.add("Password cannot exceed $MAX_LENGTH characters.")
        }

        if (!password.any { it.isUpperCase() }) {
            errors.add("Password must contain at least one uppercase letter (A-Z).")
        }

        if (!password.any { it.isLowerCase() }) {
            errors.add("Password must contain at least one lowercase letter (a-z).")
        }

        if (!password.any { it.isDigit() }) {
            errors.add("Password must contain at least one number (0-9).")
        }

        val hasSpecial = password.any { !it.isLetterOrDigit() && !it.isWhitespace() }
        if (!hasSpecial) {
            errors.add("Password must contain at least one special symbol (!@#$%^&*...).")
        }

        if (password != confirmation) {
            errors.add("Password confirmation does not match.")
        }

        val strength = evaluateStrength(password)
        val isValid = errors.isEmpty()

        return PasswordValidationResult(
            isValid = isValid,
            strength = strength,
            errors = errors
        )
    }

    /**
     * Evaluates the relative strength of a password based on length, character set variety, and entropy.
     */
    fun evaluateStrength(password: String): PasswordStrength {
        if (password.length < MIN_LENGTH || password.any { it.isWhitespace() }) {
            return PasswordStrength.WEAK
        }

        val hasUpper = password.any { it.isUpperCase() }
        val hasLower = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecial = password.any { !it.isLetterOrDigit() && !it.isWhitespace() }

        val charsetDiversity = listOf(hasUpper, hasLower, hasDigit, hasSpecial).count { it }

        if (charsetDiversity < 3) {
            return PasswordStrength.WEAK
        }

        if (password.length >= RECOMMENDED_VERY_STRONG_LENGTH && charsetDiversity == 4) {
            return PasswordStrength.VERY_STRONG
        }

        if (password.length >= RECOMMENDED_STRONG_LENGTH && charsetDiversity >= 3) {
            return PasswordStrength.STRONG
        }

        if (charsetDiversity >= 4) {
            return PasswordStrength.STRONG
        }

        return PasswordStrength.FAIR
    }
}
