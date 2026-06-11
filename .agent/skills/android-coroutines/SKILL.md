---
name: android-coroutines
description: "Implement and review Kotlin Coroutines on Android: configure CoroutineScopes, manage Dispatchers, expose StateFlow/SharedFlow from ViewModels, collect with repeatOnLifecycle, and convert callback APIs to callbackFlow. Use when writing suspend functions, wiring up Flow or StateFlow, fixing async bugs, replacing GlobalScope, or integrating coroutines with Android lifecycle components."
---

# Android Coroutines Expert Skill

## Workflow

1. **Identify scope** — Determine the correct CoroutineScope (`viewModelScope`, `lifecycleScope`, or injected `applicationScope`).
2. **Wire data layer** — Expose data as `suspend` functions (one-shot) or `Flow` (streams) with injected Dispatchers.
3. **Connect UI** — Collect flows using `repeatOnLifecycle(Lifecycle.State.STARTED)` and expose read-only `StateFlow`.
4. **Verify** — Run `./gradlew test` and confirm coroutine behavior. If tests fail, check: uncaught `CancellationException` in generic `catch` blocks, `GlobalScope` usage causing leaked coroutines, missing `awaitClose` in `callbackFlow`, or hardcoded Dispatchers breaking test determinism. Run `./gradlew detektDebug` to catch structural issues.

## Critical Rules

### 1. Dispatcher Injection (Testability)
*   **NEVER** hardcode Dispatchers (e.g., `Dispatchers.IO`, `Dispatchers.Default`) inside classes.
*   **ALWAYS** inject a `CoroutineDispatcher` via the constructor.
*   **DEFAULT** to `Dispatchers.IO` in the constructor argument for convenience, but allow it to be overridden.

```kotlin
// CORRECT
class UserRepository(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) { ... }

// INCORRECT
class UserRepository {
    fun getData() = withContext(Dispatchers.IO) { ... }
}
```

### 2. Main-Safety
*   All suspend functions in Data/Domain layers must be **main-safe** — use `withContext(dispatcher)` internally.
*   **One-shot calls**: expose as `suspend` functions. **Data streams**: expose as `Flow`.

### 3. Lifecycle-Aware Collection
*   **NEVER** collect a flow directly in `lifecycleScope.launch` or `launchWhenStarted` (deprecated/unsafe).
*   **ALWAYS** use `repeatOnLifecycle(Lifecycle.State.STARTED)` for collecting flows in Activities or Fragments.

```kotlin
// CORRECT
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { ... }
    }
}
```

### 4. ViewModel Scope Usage
*   Use `viewModelScope` for initiating coroutines in ViewModels.
*   Do not expose suspend functions from the ViewModel to the View. The ViewModel should expose `StateFlow` or `SharedFlow` that the View observes.

### 5. Mutable State Encapsulation
*   **NEVER** expose `MutableStateFlow` or `MutableSharedFlow` publicly.
*   Expose them as read-only `StateFlow` or `Flow` using `.asStateFlow()` or upcasting.

### 6. GlobalScope Prohibition
*   **NEVER** use `GlobalScope`. It breaks structured concurrency and leads to leaks.
*   If a task must survive the current scope, use an injected `applicationScope` (a custom scope tied to the Application lifecycle).

### 7. Exception Handling
*   **NEVER** catch `CancellationException` in a generic `catch (e: Exception)` block without rethrowing it.
*   Use `runCatching` only if you explicitly rethrow `CancellationException`.
*   Use `CoroutineExceptionHandler` only for top-level coroutines (inside `launch`). It has no effect inside `async` or child coroutines.

### 8. Cancellability
*   **ALWAYS** call `ensureActive()` or `yield()` in tight loops (e.g., processing a large list, reading files).
*   `delay()` and `withContext()` are already cancellable — no extra checks needed there.

### 9. Callback Conversion
*   Use `callbackFlow` to convert callback-based APIs to Flow.
*   **ALWAYS** use `awaitClose` at the end of the `callbackFlow` block to unregister listeners.

## Code Patterns

### Repository Pattern with Flow

```kotlin
class NewsRepository(
    private val remoteDataSource: NewsRemoteDataSource,
    private val externalScope: CoroutineScope, // For app-wide events
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val newsUpdates: Flow<List<News>> = flow {
        val news = remoteDataSource.fetchLatestNews()
        emit(news)
    }.flowOn(ioDispatcher) // Upstream executes on IO
}
```

### Parallel Execution

```kotlin
suspend fun loadDashboardData() = coroutineScope {
    val userDeferred = async { userRepo.getUser() }
    val feedDeferred = async { feedRepo.getFeed() }
    
    // Wait for both
    DashboardData(
        user = userDeferred.await(),
        feed = feedDeferred.await()
    )
}
```

### Testing with runTest

```kotlin
@Test
fun testViewModel() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val viewModel = MyViewModel(testDispatcher)
    
    viewModel.loadData()
    advanceUntilIdle() // Process coroutines
    
    assertEquals(expectedState, viewModel.uiState.value)
}
```
