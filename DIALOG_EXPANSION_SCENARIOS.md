# Dialog Expansion Scenarios - How It Works

## Overview
The hierarchy dialog stays in sync with the graph view. When you expand/collapse nodes, both views update together.

## Code Flow

### Key State Variables
- `expandedNodeIds`: Nodes that are expanded in the graph (showing their children)
- `visibleNodeIds`: Nodes that are visible in the graph
- `localExpandedNodes`: Dialog-specific expansion state (now synced with expandedNodeIds)

### Key Functions
1. `handleDialogExpand(nodeId)`: Called when clicking expand/collapse arrow in dialog
2. `handleToggleExpansion(nodeId)`: Called when expanding/collapsing in graph
3. `findPathToNode(nodeId)`: Finds path from root to target node

---

## Scenario 1: Expand a Visible Node in Dialog

**Initial State:**
```
Graph: Measurement [expanded]
         └─ Circulatory [visible, collapsed]

Dialog: Measurement ▼
         └─ Circulatory ▶
```

**User Action:** Click expand arrow (▶) next to "Circulatory" in dialog

**Code Flow:**
```javascript
handleDialogExpand("Circulatory") {
  // Check if collapsing - NO (not expanded)
  isCurrentlyExpanded = false
  
  // Check if visible - YES
  visibleNodeIds.has("Circulatory") = true
  
  // Call handleToggleExpansion
  handleToggleExpansion("Circulatory") {
    // Get children
    children = getChildren("Circulatory") // Returns ["Cardiovascular"]
    
    // Update state
    newVisibleIds.add("Cardiovascular")
    newExpandedIds.add("Circulatory")
    
    setVisibleNodeIds(newVisibleIds)
    setExpandedNodeIds(newExpandedIds)
    setLocalExpandedNodes(newExpandedIds) // Sync dialog
  }
}
```

**Result:**
```
Graph: Measurement [expanded]
         └─ Circulatory [expanded]
              └─ Cardiovascular [visible, collapsed]

Dialog: Measurement ▼
         └─ Circulatory ▼
              └─ Cardiovascular ▶
```

**Console Logs:**
```
[Dialog] User clicked to expand/collapse node: Circulatory
[Dialog] Node currently visible in graph? true
[Dialog] Node is visible, expanding in graph
[UI] User clicked to toggle expansion for: Circulatory
[UI] Current state - Visible: 2, Expanded: 1
[Hierarchy] Expanded "Circulatory", added 1 children
[UI] Action: expanded
[UI] New state - Visible: 3, Expanded: 2
```

---

## Scenario 2: Collapse an Expanded Node in Dialog

**Initial State:**
```
Graph: Measurement [expanded]
         └─ Circulatory [expanded]
              └─ Cardiovascular [visible]

Dialog: Measurement ▼
         └─ Circulatory ▼
              └─ Cardiovascular ▶
```

**User Action:** Click collapse arrow (▼) next to "Circulatory" in dialog

**Code Flow:**
```javascript
handleDialogExpand("Circulatory") {
  // Check if collapsing - YES
  isCurrentlyExpanded = localExpandedNodes.has("Circulatory") = true
  
  // Collapsing - call handleToggleExpansion
  handleToggleExpansion("Circulatory") {
    // Node is expanded, so collapse it
    expandedNodeIds.has("Circulatory") = true
    
    // Get all descendants to remove
    toRemove = getAllDescendants("Circulatory") // Returns ["Cardiovascular"]
    
    // Update state
    newVisibleIds.delete("Cardiovascular")
    newExpandedIds.delete("Circulatory")
    
    setVisibleNodeIds(newVisibleIds)
    setExpandedNodeIds(newExpandedIds)
    setLocalExpandedNodes(newExpandedIds) // Sync dialog
  }
}
```

**Result:**
```
Graph: Measurement [expanded]
         └─ Circulatory [collapsed]

Dialog: Measurement ▼
         └─ Circulatory ▶
```

**Console Logs:**
```
[Dialog] User clicked to expand/collapse node: Circulatory
[Dialog] Node currently visible in graph? true
[UI] User clicked to toggle expansion for: Circulatory
[UI] Current state - Visible: 3, Expanded: 2
[Hierarchy] Collapsed "Circulatory", removed 1 descendants
[UI] Action: collapsed
[UI] New state - Visible: 2, Expanded: 1
```

---

## Scenario 3: Expand a Non-Visible Node in Dialog (Auto-Expand Parents)

**Initial State:**
```
Graph: Measurement [visible, collapsed]

Dialog: Measurement ▶
         (Circulatory not visible yet)
         (Cardiovascular not visible yet)
```

**User Action:** Click "Cardiovascular" in dialog (deep nested node)

**Code Flow:**
```javascript
handleDialogExpand("Cardiovascular") {
  // Check if collapsing - NO
  isCurrentlyExpanded = false
  
  // Check if visible - NO
  visibleNodeIds.has("Cardiovascular") = false
  
  // Find path from root to target
  path = findPathToNode("Cardiovascular") 
  // Returns: ["Measurement", "Circulatory", "Cardiovascular"]
  
  // Auto-expand all ancestors
  newVisibleIds = new Set(visibleNodeIds)
  newExpandedIds = new Set(expandedNodeIds)
  
  // Loop through path (excluding last node)
  for i=0 to 1: // Measurement, Circulatory
    // i=0: Expand Measurement
    if (!newExpandedIds.has("Measurement")) {
      children = getChildren("Measurement") // ["Circulatory"]
      newVisibleIds.add("Circulatory")
      newExpandedIds.add("Measurement")
    }
    
    // i=1: Expand Circulatory
    if (!newExpandedIds.has("Circulatory")) {
      children = getChildren("Circulatory") // ["Cardiovascular"]
      newVisibleIds.add("Cardiovascular")
      newExpandedIds.add("Circulatory")
    }
  
  // Expand target node itself
  children = getChildren("Cardiovascular") // ["HeartRate", "BloodPressure", ...]
  children.forEach(childId => newVisibleIds.add(childId))
  newExpandedIds.add("Cardiovascular")
  
  setVisibleNodeIds(newVisibleIds)
  setExpandedNodeIds(newExpandedIds)
  setLocalExpandedNodes(newExpandedIds) // Sync dialog
}
```

**Result:**
```
Graph: Measurement [expanded]
         └─ Circulatory [expanded]
              └─ Cardiovascular [expanded]
                   ├─ Heart Rate [visible]
                   ├─ Blood Pressure [visible]
                   └─ ...

Dialog: Measurement ▼
         └─ Circulatory ▼
              └─ Cardiovascular ▼
                   ├─ Heart Rate ▶
                   ├─ Blood Pressure ▶
                   └─ ...
```

**Console Logs:**
```
[Dialog] User clicked to expand/collapse node: Cardiovascular
[Dialog] Node currently visible in graph? false
[Dialog] Node is NOT visible in graph yet. Need to expand parent first.
[Dialog] Path to node: ["Measurement", "Circulatory", "Cardiovascular"]
[Dialog] Auto-expanded ancestor: Measurement
[Dialog] Auto-expanded ancestor: Circulatory
[Dialog] Expanded target node and ancestors. New visible count: 8
```

---

## Scenario 4: Expand Node in Graph (Dialog Syncs Automatically)

**Initial State:**
```
Graph: Measurement [visible, collapsed]

Dialog: Measurement ▶
```

**User Action:** Double-click "Measurement" node in graph

**Code Flow:**
```javascript
handleToggleExpansion("Measurement") {
  // Get children
  children = getChildren("Measurement") // ["Circulatory"]
  
  // Update state
  newVisibleIds.add("Circulatory")
  newExpandedIds.add("Measurement")
  
  setVisibleNodeIds(newVisibleIds)
  setExpandedNodeIds(newExpandedIds)
  setLocalExpandedNodes(newExpandedIds) // Sync dialog ← KEY!
}

// Dialog re-renders because expandedNodeIds changed
renderHierarchyTree(node) {
  isExpanded = expandedNodeIds.has("Measurement") // Now TRUE
  // Chevron changes from ▶ to ▼
  // Children become visible
}
```

**Result:**
```
Graph: Measurement [expanded]
         └─ Circulatory [visible]

Dialog: Measurement ▼
         └─ Circulatory ▶
```

---

## Scenario 5: Expand Parent Button (ChevronUp) in Dialog

**Initial State:**
```
Graph: Circulatory [visible at root]

Dialog: Circulatory ▲ (has parents)
```

**User Action:** Click up arrow (▲) next to "Circulatory" in dialog

**Code Flow:**
```javascript
handleExpandParents("Circulatory") {
  // Get parent IRIs
  parentIds = getParents("Circulatory") // ["Measurement"]
  
  // Add parents to visible set
  newVisibleIds.add("Measurement")
  
  // Add edges from parents to current node
  parentEdges.forEach(edge => {
    newVisibleIds.add(edge.from) // Measurement
    newVisibleIds.add(edge.to)   // Circulatory
  })
  
  // Mark parents as expanded
  newExpandedIds.add("Measurement")
  
  setVisibleNodeIds(newVisibleIds)
  setExpandedNodeIds(newExpandedIds)
  
  // Update dialog to show topmost parent as root
  setHierarchyRootNode("Measurement")
  
  // Expand path in dialog
  newLocalExpanded.add("Measurement")
  newLocalExpanded.add("Circulatory")
  setLocalExpandedNodes(newLocalExpanded)
}
```

**Result:**
```
Graph: Measurement [expanded]
         └─ Circulatory [visible]

Dialog: Measurement ▼
         └─ Circulatory ▶
```

---

## Key Synchronization Points

### 1. Graph → Dialog Sync
When `expandedNodeIds` changes, the dialog automatically updates because:
```javascript
renderHierarchyTree() {
  isExpanded = expandedNodeIds.has(node.id) // Uses graph state
  // ...
}
// Dependencies: [nodeRelationsMap, allNodes, expandedNodeIds, ...]
```

### 2. Dialog → Graph Sync
When dialog expands/collapses, it updates both states:
```javascript
handleToggleExpansion() {
  setExpandedNodeIds(newExpandedIds)
  setLocalExpandedNodes(newExpandedIds) // ← Sync
}
```

### 3. Chevron Icon Logic
```javascript
{hasChildNodes && (
  <button onClick={() => handleDialogExpand(node.id)}>
    {isExpanded ? <ChevronDown /> : <ChevronRight />}
  </button>
)}

// isExpanded = expandedNodeIds.has(node.id)
// So chevron always reflects graph state
```

---

## Edge Cases Handled

### 1. Clicking Already Expanded Node
- Collapses the node
- Removes all descendants from view
- Updates both dialog and graph

### 2. Clicking Deeply Nested Node
- Finds full path to root
- Auto-expands all ancestors
- Shows complete branch

### 3. Multiple Expand Operations
- State updates are batched
- Only final state triggers re-render
- Console logs show each step

### 4. Dialog Closed and Reopened
- `localExpandedNodes` initialized from `expandedNodeIds`
- Dialog shows current graph state
- No desync issues

---

## Visual State Summary

| Action | Graph State | Dialog State | Sync Method |
|--------|-------------|--------------|-------------|
| Expand in Dialog | Updates via `handleToggleExpansion` | Updates via `setLocalExpandedNodes` | Same Set object |
| Collapse in Dialog | Updates via `handleToggleExpansion` | Updates via `setLocalExpandedNodes` | Same Set object |
| Expand in Graph | Updates via `setExpandedNodeIds` | Updates via `setLocalExpandedNodes` | Copy Set |
| Auto-expand Parents | Updates via `setExpandedNodeIds` | Updates via `setLocalExpandedNodes` | Copy Set |

All operations maintain: **`localExpandedNodes === expandedNodeIds`**
