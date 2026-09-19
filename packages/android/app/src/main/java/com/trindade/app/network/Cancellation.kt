package com.trindade.app.network

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching], except that a cancellation is rethrown instead of captured.
 *
 * `runCatching` catches `Throwable`, and on the JVM a cancellation -- `CancellationException`, which
 * is `java.util.concurrent.CancellationException` -- is one of them. A repository that wraps a
 * suspending call in it therefore turns a cancelled call into an ordinary failure twice over. The
 * caller is handed `null`, which every screen here reads as "the server could not be asked" and shows
 * as no connection, so an operator who left the screen is told about a network that is fine. And the
 * coroutine reports success rather than cancelled, which is the half that matters more: the parent
 * that cancelled this work keeps waiting on a job that has already been abandoned, and the fact that
 * anybody gave up is lost rather than propagated.
 *
 * `inline` is load-bearing rather than a micro-optimisation. The blocks at the call sites call
 * suspending functions, and that compiles only because the block is inlined into the caller, which is
 * already a suspend function.
 *
 * Every other `Throwable` is still captured as a failed [Result], exactly as `runCatching` captured
 * it. That is the behaviour being preserved rather than a judgement about those throwables: this
 * helper exists for the one exception that carries "this work was abandoned", not as a second opinion
 * on how a transport failure should be reported.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
