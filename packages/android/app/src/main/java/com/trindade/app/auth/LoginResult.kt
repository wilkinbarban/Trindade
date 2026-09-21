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
 *
 * [Unreachable] carries [UnreachableCause] rather than standing for every failure of its own, and that
 * is the same correction one step further in. One sentence was shown for all of them, so a server that
 * answered too slowly, a name that did not resolve, a refused connection and a reply nobody could parse
 * arrived at the operator as "Sem conexão com o servidor" -- and the second of those is not a network
 * the operator can go and check. Nothing about the request had to change to tell them apart: the
 * Throwable that said which one it was had always arrived, and a `getOrNull()` had thrown it away before
 * anybody could read it.
 */
sealed interface LoginResult {
    data object Success : LoginResult

    /** The server answered and refused. [message] is its own text, or a fallback when it sent none. */
    data class Rejected(val message: String) : LoginResult

    /**
     * The request never got an answer.
     *
     * [cause] is the half a reader needs, because the four kinds call for different sentences and only
     * some of them are about a network the operator can act on. It is carried rather than only logged:
     * the log line in the repository is for whoever reads the device, and the screen is for whoever is
     * holding it.
     */
    data class Unreachable(val cause: UnreachableCause) : LoginResult
}

/**
 * Why a request produced no answer.
 *
 * Read out of the Throwable that ended the call rather than guessed from a status code, because a call
 * that never returned a status code is the whole case this exists for. The classes named below are the
 * ones OkHttp and the JSON converter actually raise, so a reader can follow each value back to the code
 * that produces it: `AuthRepository.unreachableCause` is that mapping and the only place it is made.
 *
 * The log line repeats the exception's own type and message as well, which is deliberate rather than a
 * second copy: this taxonomy names the failures worth a different sentence on screen, and a failure it
 * cannot name is still worth reading on the device exactly as it arrived.
 */
enum class UnreachableCause {
    /**
     * The client's own bound expired while the request was still in flight.
     *
     * `java.net.SocketTimeoutException` is the socket's own, and a bare
     * `java.io.InterruptedIOException` is what OkHttp raises on the paths where it timed the call
     * itself -- and the first is a kind of the second, so the pair is written out for the reader rather
     * than for the filter.
     *
     * The one kind with a sentence of its own on the login screen, because it is the only one that is
     * false about the sentence the others keep: a timeout is a server that was slow, not one that was
     * not there.
     */
    Timeout,

    /**
     * There was nothing to talk to.
     *
     * `java.net.UnknownHostException` for a name that did not resolve, and `java.net.SocketException`
     * for everything the socket itself refused -- the `java.net.ConnectException` of a rejected
     * connection, and the resets that end one that had already started.
     */
    NoRoute,

    /**
     * The connection was made and the transport under it failed: `javax.net.ssl.SSLException`, which
     * covers a handshake this phone and this server could not agree on and a stream that broke inside
     * an established session.
     *
     * Kept apart from [NoRoute] although both leave the operator with nothing to do but try again,
     * because it is the one failure whose cause is plausibly on this side of the network -- a clock, a
     * certificate, an intercepting proxy -- and it is invisible from a logcat line that says "connection".
     */
    Tls,

    /**
     * Something answered and what came back could not be read as this client's envelope:
     * strictly for deserialization failures (`kotlinx.serialization.SerializationException`),
     * where the converter's own exception arrives out of the call instead of a status code.
     */
    UnreadableBody,

    /**
     * Any unclassified residual Throwable that this taxonomy does not explicitly name.
     * The log line carries its type and message so the reader is not left in the dark.
     */
    Unknown,
}

/**
 * What a screen should put in front of the user after a failed attempt.
 *
 * Split by origin rather than carried as one string, because the two are not the same kind of thing:
 * text from the server is data and is shown as it arrives, while a failure to reach the server is a
 * condition this app has its own words for. Folding both into a String field put UI copy inside a
 * ViewModel and made the translation of one of them somebody else's problem.
 *
 * The app's own words are partitioned according to [UnreachableCause]: timeouts get [Timeout],
 * TLS failures get [Tls], unreadable responses get [UnreadableBody], and missing routes or unknown
 * errors get [Unreachable].
 */
sealed interface LoginMessage {
    /** The contract's own words, which describe what actually happened better than the client can. */
    data class FromServer(val text: String) : LoginMessage

    /**
     * The client stopped waiting on an answer that was still coming: the server was slow, not absent.
     *
     * Its own case because the sentence it used to get is false about it, and because the operator's next
     * move differs: there is no network to go and check, and telling them to check one sends them looking
     * for a fault that is not there.
     */
    data object Timeout : LoginMessage

    /**
     * A TLS handshake or secure transport failure occurred between the client and the server.
     */
    data object Tls : LoginMessage

    /**
     * The server answered, but the response could not be parsed as a recognized contract envelope.
     */
    data object UnreadableBody : LoginMessage

    /** Nothing answered or no route to host was available, so there are no server words to repeat. */
    data object Unreachable : LoginMessage
}
