# Graph View Plugin - Robustness & Performance Improvements

## Overview
This document identifies critical robustness issues, performance bottlenecks, and recommended improvements for the Graph View Plugin. Based on code review of data fetching, caching, layout algorithms, and error handling.

---

## Critical Issues (P0 - Must Fix)

### 1. **Missing Error Boundaries in Async Operations**
**File:** `GraphDataFetchService.ts`, `GraphDataService.ts`
**Severity:** P0  
**Description:** 
- Fetch operations lack proper error boundaries
- Promise.all() in `fetchGraphData()` can fail silently if one endpoint fails
- No retry logic for transient failures (network glitches, timeouts)
- No timeout protection for long-running operations

**Current Code Issue:**
```typescript
// Line 46-52: Promise.all with no granular error handling
const [classesData, individualsData, objectPropsData, dataPropsData, 
        annotationPropsData, datatypesData] = await Promise.all([
  this.fetchAllClassesRecursively(),  // If this fails, entire operation fails
  this.fetchIndividuals(),
  // ... others
]);
```

**Recommendation:**
```typescript
// Wrap each fetch in try-catch or use Promise.allSettled()
const results = await Promise.allSettled([
  this.fetchAllClassesRecursively(),
  this.fetchIndividuals(),
  // ...
]);

// Handle failures gracefully with fallbacks
const classesData = results[0].status === 'fulfilled' ? results[0].value : [];
```

**Implementation Steps:**
- [ ] Replace `Promise.all()` with `Promise.allSettled()` for entity fetches
- [ ] Add per-endpoint retry logic (exponential backoff, max 3 retries)
- [ ] Add 30s timeout to all fetch operations
- [ ] Log which endpoints failed and why
- [ ] Return partial data instead of complete failure

---

### 2. **Memory Leaks in Recursive Child Fetching**
**File:** `GraphDataFetchService.ts` line 186-206  
**Severity:** P0  
**Description:**
- `fetchChildren()` creates circular references in visited Set
- Multiple simultaneous fetches can consume unbounded memory
- No limit on recursion depth for pathological ontologies
- Child fetch responses not properly garbage collected

**Current Code Issue:**
```typescript
const visited = new Set<string>();
const fetchChildren = async (parentIri: string): Promise<any[]> => {
  if (visited.has(parentIri)) return [];
  visited.add(parentIri);  // Never cleaned up
  // Recursive calls with no depth limit
};
```

**Recommendation:**
```typescript
const fetchChildren = async (parentIri: string, depth = 0, maxDepth = 5): Promise<any[]> => {
  if (depth > maxDepth) {
    console.warn(`Max recursion depth reached for ${parentIri}`);
    return [];
  }
  // ...
};
```

**Implementation Steps:**
- [ ] Add `maxDepth` parameter to `fetchChildren()` (default: 5)
- [ ] Use WeakMap for visited tracking (allows GC)
- [ ] Add memory usage monitoring
- [ ] Paginate large result sets (limit=1000 per fetch)
- [ ] Add warning logs when depth limit reached

---

### 3. **Unhandled Floating-Point Edge Cases in Fuzzy Logic**
**File:** Need to implement in fuzzy membership function code  
**Severity:** P0  
**Description:**
- Triangular membership: division by zero when `b - a = 0` or `c - b = 0`
- Gaussian membership: overflow in exponential when σ is very small
- No validation that `a < b < c` for triangular functions
- No handling of NaN/Infinity propagation

**Recommendation:**
```typescript
function triangularMembership(x: number, a: number, b: number, c: number): number {
  // Validate parameters
  if (!(a < b && b < c)) {
    throw new RangeError(`Invalid parameters: ${a} < ${b} < ${c} must hold`);
  }
  if (x <= a || x >= c) return 0.0;
  
  // Avoid division by zero
  const riseDenom = b - a;
  if (riseDenom === 0) return x === b ? 1.0 : 0.0;
  
  if (x <= b) {
    return (x - a) / riseDenom;
  } else {
    const fallDenom = c - b;
    return fallDenom === 0 ? 0.0 : (c - x) / fallDenom;
  }
}
```

**Implementation Steps:**
- [ ] Add parameter validation for all membership functions
- [ ] Use epsilon comparisons (1e-10) for floating-point equality
- [ ] Clamp results to [0.0, 1.0] range
- [ ] Add unit tests for edge cases (TC-FZ-005)
- [ ] Document precision limits (double precision: 15-17 digits)

---

### 4. **Missing Authorization Header Propagation**
**File:** `GraphDataFetchService.ts` line 28-29, all fetch calls  
**Severity:** P0  
**Description:**
- Headers are hardcoded inline in every fetch call
- No fallback if authToken is null
- Authorization header not propagated through all endpoints
- Potential for unauthenticated requests to succeed accidentally

**Current Code Issue:**
```typescript
private get headers() {
  return {
    'Authorization': `Bearer ${this.authToken}`  // Null if not set
  };
}
// Used in all fetches - could succeed without auth
```

**Recommendation:**
```typescript
private getHeaders(): Record<string, string> {
  if (!this.authToken) {
    throw new AuthenticationError('No auth token available');
  }
  return {
    'Authorization': `Bearer ${this.authToken}`,
    'Content-Type': 'application/json',
    'X-Request-ID': generateRequestId()  // For debugging
  };
}
```

**Implementation Steps:**
- [ ] Validate authToken exists before making requests
- [ ] Throw explicit AuthenticationError if missing
- [ ] Add request correlation ID for debugging
- [ ] Add auth refresh logic for expired tokens
- [ ] Log all failed auth attempts

---

## High Priority Issues (P1)

### 5. **Cache Invalidation Race Condition**
**File:** `GraphDataService.ts` line 56-73  
**Severity:** P1  
**Description:**
- Cache entry can be read between clear() and new fetch()
- Multiple concurrent requests can create duplicate cache entries
- TTL not checked before returning cached data
- No atomic cache operations

**Current Code Issue:**
```typescript
async clearProjectCache(projectId: string): Promise<void> {
  // Clear local cache
  for (const key of Array.from(this.cache.keys())) {
    if (key.includes(projectId)) {
      this.cache.delete(key);  // Race: new fetch can start here
    }
  }
}
```

**Recommendation:**
```typescript
private cacheVersion: Map<string, number> = new Map();

async clearProjectCache(projectId: string): Promise<void> {
  const version = (this.cacheVersion.get(projectId) || 0) + 1;
  this.cacheVersion.set(projectId, version);  // Invalidate all entries atomically
  
  for (const key of Array.from(this.cache.keys())) {
    if (key.includes(projectId)) {
      this.cache.delete(key);
    }
  }
}
```

**Implementation Steps:**
- [ ] Add cache versioning scheme
- [ ] Use atomic versioning for clear operations
- [ ] Add mutex lock for concurrent cache access
- [ ] Implement stale-while-revalidate pattern
- [ ] Add cache hit/miss metrics

---

### 6. **D3 Layout Crashes on Pathological Graphs**
**File:** `HierarchicalLayout.ts`, `CircularLayout.ts`, `GridLayout.ts`  
**Severity:** P1  
**Description:**
- No bounds checking for canvas dimensions
- Infinite loops possible in cycle detection
- NaN propagation when computing node positions
- D3 simulation can hang on disconnected components

**Current Code Issue:**
```typescript
// Line 51-58: No validation of hierarchy
const roots = nodes.filter(node => !childToParent.has(node.id));

if (roots.length === 0) {
  return applyGridLayout(nodes, width, height);  // Grid layout might also fail
}
```

**Recommendation:**
```typescript
export function applyHierarchicalLayout(
  nodes: OntologyNode[],
  edges: OntologyEdge[],
  options: HierarchicalLayoutOptions
): Map<string, { x: number; y: number }> {
  // Validate inputs
  if (!nodes || nodes.length === 0) {
    return new Map();
  }
  if (options.width <= 0 || options.height <= 0) {
    throw new Error('Invalid canvas dimensions');
  }
  
  // Detect and warn about cycles
  const hasCycle = detectCycles(edges);
  if (hasCycle) {
    console.warn('Graph contains cycles; applying acyclic projection');
  }
  
  // ... rest of layout
}

function detectCycles(edges: OntologyEdge[]): boolean {
  const visited = new Set<string>();
  const recursionStack = new Set<string>();
  
  function hasCycleDFS(node: string): boolean {
    visited.add(node);
    recursionStack.add(node);
    
    for (const edge of edges.filter(e => e.from === node)) {
      if (!visited.has(edge.to)) {
        if (hasCycleDFS(edge.to)) return true;
      } else if (recursionStack.has(edge.to)) {
        return true;
      }
    }
    
    recursionStack.delete(node);
    return false;
  }
  
  for (const edge of edges) {
    if (!visited.has(edge.from) && hasCycleDFS(edge.from)) {
      return true;
    }
  }
  return false;
}
```

**Implementation Steps:**
- [ ] Add input validation to all layout functions
- [ ] Implement cycle detection
- [ ] Add dimension bounds checking
- [ ] Test with malformed graphs (100k nodes, complex cycles)
- [ ] Add error recovery (fallback to grid layout)

---

### 7. **No Timeout for Long-Running Queries**
**File:** `GraphDataService.ts` line 144-155  
**Severity:** P1  
**Description:**
- Query execution can hang indefinitely
- No AbortController timeout mechanism
- Browser may become unresponsive during long queries
- No progress reporting to user

**Current Code Issue:**
```typescript
async executeQuery(projectId: string, query: GraphQuery): Promise<any> {
  const response = await fetch(`${this.baseUrl}/${projectId}/graph/query`, {
    method: 'POST',
    // No timeout specified
  });
}
```

**Recommendation:**
```typescript
async executeQuery(
  projectId: string,
  query: GraphQuery,
  timeoutMs: number = 30000
): Promise<any> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
  
  try {
    const response = await fetch(`${this.baseUrl}/${projectId}/graph/query`, {
      method: 'POST',
      signal: controller.signal,
      // ...
    });
    
    if (!response.ok) {
      throw new Error(`Query failed: ${response.statusText}`);
    }
    
    return await response.json();
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') {
      throw new TimeoutError(`Query exceeded ${timeoutMs}ms timeout`);
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
}

class TimeoutError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TimeoutError';
  }
}
```

**Implementation Steps:**
- [ ] Add timeout parameter to executeQuery()
- [ ] Use AbortController for cancellation
- [ ] Add progress callback for long queries
- [ ] Emit timeout events for UI feedback
- [ ] Add metrics tracking query duration

---

## Medium Priority Issues (P2)

### 8. **Inefficient Search Implementation**
**File:** Need to review search-related code  
**Severity:** P2  
**Description:**
- Linear search through all nodes is O(n)
- No indexing for fast lookups
- Search happens on every keystroke without debouncing
- No fuzzy search support

**Recommendation:**
- Implement trie data structure for autocomplete
- Add debounce (300ms) to search input
- Build inverted index of node labels/properties
- Support regex and fuzzy matching

---

### 9. **Missing Performance Monitoring**
**File:** All service files  
**Severity:** P2  
**Description:**
- No metrics on fetch time, cache hit rate, layout rendering
- No alerting for performance degradation
- Hard to debug performance issues in production

**Recommendation:**
```typescript
class PerformanceMonitor {
  private metrics: Record<string, number[]> = {};
  
  mark(operation: string, durationMs: number) {
    if (!this.metrics[operation]) {
      this.metrics[operation] = [];
    }
    this.metrics[operation].push(durationMs);
    
    const avg = this.metrics[operation].reduce((a, b) => a + b, 0) / 
                this.metrics[operation].length;
    
    if (avg > 1000) {
      console.warn(`Slow operation: ${operation} = ${avg.toFixed(2)}ms`);
    }
  }
}
```

---

## Improvements Summary

### Quick Wins (< 1 day each)
1. Add input validation to membership functions (TC-FZ-005)
2. Replace Promise.all() with Promise.allSettled()
3. Add cycle detection to layout algorithms
4. Add timeout to query execution

### Medium Effort (1-3 days each)
5. Implement cache versioning for atomicity
6. Add performance monitoring framework
7. Implement search indexing with debounce
8. Add retry logic with exponential backoff

### Strategic (1+ weeks)
9. Refactor error handling with custom error classes
10. Add comprehensive logging with correlation IDs
11. Implement progressive data loading (streaming)
12. Add observability (metrics, traces, logs)

---

## Testing Recommendations

### Unit Tests to Add
- [ ] Membership function edge cases (TC-FZ-005 test cases)
- [ ] Cycle detection algorithm
- [ ] Cache versioning atomicity
- [ ] Error propagation from Promise.allSettled()

### Integration Tests
- [ ] Query timeout behavior
- [ ] Cache invalidation under concurrent loads
- [ ] Layout rendering with 100k+ nodes
- [ ] Auth token refresh flow

### Performance Tests
- [ ] Graph fetch time with 1000+ classes
- [ ] Cache hit rate metrics
- [ ] D3 simulation FPS at scale
- [ ] Memory usage baseline and growth

---

## Implementation Checklist

- [ ] **P0 Issues:** Complete all critical issues before production deployment
- [ ] **P1 Issues:** Schedule for next 2 sprints
- [ ] **P2 Issues:** Add to backlog for continuous improvement
- [ ] **Testing:** Implement corresponding test cases for all changes
- [ ] **Documentation:** Update README with new error handling patterns
- [ ] **Monitoring:** Set up alerts for performance degradation

---

## Related Test Cases
- TC-FZ-005: Edge Cases and Boundary Conditions (fuzzy robustness)
- TC-GV-026: Performance at Scale (layout robustness)
- TC-GV-037: Invalid Fuzzy Input Robustness (validation)
- TC-GV-016: Error Handling (fetch service robustness)
