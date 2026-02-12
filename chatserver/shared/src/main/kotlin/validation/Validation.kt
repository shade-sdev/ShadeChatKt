package validation

import dto.*
import io.konform.validation.Validation
import io.konform.validation.constraints.maxItems
import io.konform.validation.constraints.maxLength
import io.konform.validation.constraints.minLength
import io.konform.validation.constraints.pattern


/**
 * Input validation rules - prevents XSS, SQL injection, malformed data
 */

val validateRegisterRequest = Validation {
    RegisterRequest::username {
        minLength(3) hint "Username must be at least 3 characters"
        maxLength(30) hint "Username too long (max 30 characters)"
        pattern("^[a-zA-Z0-9_]+$") hint "Username can only contain letters, numbers, and underscores"
    }

    RegisterRequest::password {
        minLength(8) hint "Password must be at least 8 characters"
        maxLength(128) hint "Password too long"
    }

    RegisterRequest::displayName {
        minLength(1) hint "Display name required"
        maxLength(50) hint "Display name too long (max 50 characters)"
    }
}

val validateCreateGroupRequest = Validation {
    CreateGroupRequest::name {
        minLength(1) hint "Group name required"
        maxLength(100) hint "Group name too long (max 100 characters)"
        pattern("^[^<>\"']+$") hint "Group name contains invalid characters"
    }

    CreateGroupRequest::description ifPresent {
        maxLength(500) hint "Description too long (max 500 characters)"
    }

    CreateGroupRequest::memberIds {
        maxItems(500) hint "Too many members (max 500)"
    }
}

val validateSendMessageRequest = Validation {
    SendMessageRequest::content {
        minLength(1) hint "Message cannot be empty"
        maxLength(5000) hint "Message too long (max 5000 characters)"
    }
}

val validateUpdateUserRequest = Validation {
    UpdateUserRequest::displayName ifPresent {
        minLength(1) hint "Display name cannot be empty"
        maxLength(50) hint "Display name too long"
    }

    UpdateUserRequest::avatarUrl ifPresent {
        maxLength(500) hint "Avatar URL too long"
        pattern("^https?://.*") hint "Avatar URL must be http or https"
    }
}

/**
 * Sanitize user input - removes dangerous characters
 */
fun sanitizeInput(input: String): String {
    return input.trim()
        .replace(Regex("[<>\"']"), "") // Remove XSS characters
        .take(5000) // Enforce max length
}