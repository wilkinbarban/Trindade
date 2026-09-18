package com.trindade.app.auth

/**
 * What a signing-in attempt produced.
 *
 * A boolean was the first shape and it was wrong for one reason: the contract answers every failure
 * with an ErrorEnvelope carrying a message, and a boolean threw that away. "It did not work" is not
 * something a screen can show, and the server's message is the only text available that describes
 * what actually happened -- wrong credentials, an inactive account, or a validation error the client
 * could not have predicted.
 *
 * The three cases are kept apart because they call for different words and, in one case, different
 * actions: a rejection is the server's answer and is worth repeating, while an unreachable host is
 * not an answer at all and telling the user their credentials are wrong would be a lie.
 */
sealed interface LoginResult {
    data object Success : LoginResult

    /** The server answered and refused. [message] is its own text, or a fallback when it sent none. */
    data class Rejected(val message: String) : LoginResult

    /** The request never got an answer: no connection, a timeout, or a malformed reply. */
    data object Unreachable : LoginResult
}

/**
 * What a screen should put in front of the user after a failed attempt.
 *
 * Split by origin rather than carried as one string, because the two are not the same kind of thing:
 * text from the server is data and is shown as it arrives, while a failure to reach the server is a
 * condition this app has its own words for. Folding both into a String field put UI copy inside a
 * ViewModel and made the translation of one of them somebody else's problem.
 */
sealed interface LoginMessage {
    /** The contract's own words, which describe what actually happened better than the client can. */
    data class FromServer(val text: String) : LoginMessage

    /** Nothing answered, so there are no server words to repeat. */
    data object Unreachable : LoginMessage
}
