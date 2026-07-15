# Reasoner Quick Start Guide

## 🚀 Quick Start (3 Steps)

### Step 1: Select Reasoner
Click **Reasoner** menu → Choose **HermiT 1.4.5.519** (Recommended)

```
Menu Bar: File | Edit | View | ★ Reasoner (HermiT) ★ | Tools | Window | Help
                                    ↑
                              Click here!
```

### Step 2: Start Classification
Click **Start reasoner**

```
Reasoner Menu:
┌─────────────────────────────┐
│ ► Start reasoner            │ ← Click this
│ ☐ Synchronize reasoner      │
│ Stop reasoner               │
│ Explain inconsistent...     │
├─────────────────────────────┤
│ Configure...                │
├─────────────────────────────┤
│ Select Reasoner:            │
│ • HermiT 1.4.5.519         │ ← Selected
│   ELK 0.4.3                 │
│   Pellet                    │
│   Openllet 2.6.5            │
│   Structural Reasoner       │
└─────────────────────────────┘
```

### Step 3: View Results
Results panel appears on the right side!

```
╔══════════════════════════════════════╗
║ 🧠 Reasoner Results - HermiT        ║
╠══════════════════════════════════════╣
║  [42]      [15]      [8]      [23]  ║
║ Classes  Properties  Data   Indivs  ║
╠══════════════════════════════════════╣
║ ⚠️ Unsatisfiable Classes (2)         ║
║   • InconsistentClass1               ║
║   • InconsistentClass2               ║
╠══════════════════════════════════════╣
║ 🔗 Equivalent Classes                ║
║   Pizza ≡ ItalianDish                ║
╠══════════════════════════════════════╣
║ 🌲 Inferred Class Hierarchy          ║
║  ▸ owl:Thing                         ║
║    ▸ Pizza [inferred]   ← Hover me! ║
║      • MargheritaPizza               ║
╠══════════════════════════════════════╣
║ 🟢 Running  Auto-sync ON  3:45 PM   ║
╚══════════════════════════════════════╝
```

## 💡 Explanation Tooltips

**Hover over any class** to see WHY it was inferred:

```
         Pizza [inferred]
           ↓
    ╭────────────────────────────╮
    │ Why inferred:              │
    │                            │
    │ This class is a subclass   │
    │ of Food because it has     │
    │ property hasTopping with   │
    │ domain Food                │
    ╰────────────────────────────╯
```

## 🔄 Auto-Sync Mode

Enable **Synchronize reasoner** to automatically re-classify after changes:

1. ✅ Check "Synchronize reasoner"
2. 📝 Edit your ontology (add class, property, axiom)
3. ⏱️ Wait 2 seconds
4. 🔄 Reasoner automatically re-runs
5. ✨ Results update with new inferences!

```
Timeline:
0s: Add new class "VeganPizza"
1s: Add axiom "VeganPizza subClassOf Pizza"
2s: ⏱️ Auto-sync waits...
4s: 🔄 Reasoner runs automatically
5s: ✅ "VeganPizza" appears in inferred hierarchy!
```

## 🎯 Use Cases

### Case 1: Check Ontology Consistency
```
Problem: Is my ontology logically consistent?

Solution:
1. Start reasoner
2. Look at "Unsatisfiable Classes" section
3. If empty → ✅ Consistent
4. If classes listed → ❌ Fix contradictions
```

### Case 2: Find Equivalent Classes
```
Problem: Which classes mean the same thing?

Solution:
1. Start reasoner
2. Check "Equivalent Classes" section
3. See groups like: Pizza ≡ ItalianDish
4. Consider merging or clarifying
```

### Case 3: View Inferred Hierarchy
```
Problem: What relationships did the reasoner infer?

Solution:
1. Start reasoner
2. Browse "Inferred Class Hierarchy"
3. Classes with [inferred] badge are NEW relationships
4. Hover to see WHY they were inferred
```

### Case 4: Verify Domain/Range Axioms
```
Problem: Do my property domains/ranges work correctly?

Solution:
1. Define: hasTopping domain Pizza
2. Start reasoner
3. All classes using hasTopping should appear under Pizza
4. Check inferred hierarchy for new subclasses
```

## 🎨 Visual Indicators

### Menu Bar
```
Normal:    Reasoner (HermiT)
Running:   Reasoner 🟢 [green background + pulsing dot]
Stopped:   Reasoner (HermiT) [normal gray]
```

### Results Panel Colors
- 🟢 **Green**: Classes, good stats
- 🔵 **Blue**: Object properties, equivalent classes
- 🟠 **Orange**: Data properties
- 🟣 **Purple**: Individuals
- 🔴 **Red**: Unsatisfiable classes (problems!)
- 🟡 **Yellow**: Explanation tooltips

## ⚡ Keyboard Shortcuts (Future)

```
Ctrl+Shift+R   → Start reasoner
Ctrl+Shift+S   → Toggle synchronize
Ctrl+Shift+T   → Stop reasoner
```

## 🔧 Reasoner Comparison

| Reasoner | Speed | Completeness | Best For |
|----------|-------|--------------|----------|
| **HermiT** | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Most ontologies (default) |
| **ELK** | ⭐⭐⭐⭐⭐ | ⭐⭐ | Large EL profile ontologies |
| **Pellet** | ⭐⭐ | ⭐⭐⭐⭐⭐ | Complex OWL 2 DL features |
| **Openllet** | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Modern Pellet alternative |
| **Structural** | ⭐⭐⭐⭐⭐ | ⭐ | Quick checks only |

## ⚠️ Common Issues

### "Please load an ontology first"
**Solution**: Open an ontology file (File → Open) before starting reasoner

### Results not updating
**Solution**: 
1. Check if reasoner is running (green dot)
2. Enable auto-sync for automatic updates
3. Click "Stop" then "Start" to force refresh

### Slow classification
**Solutions**:
- Try ELK reasoner (faster, less complete)
- Disable auto-sync for large ontologies
- Reduce ontology complexity

### Many unsatisfiable classes
**Solutions**:
1. Review domain/range axioms
2. Check for contradictory restrictions
3. Look for circular definitions
4. Hover over classes for explanation

## 📊 Example Output

### Pizza Ontology Classification
```
Statistics:
  Classes: 56
  Object Properties: 12
  Data Properties: 4
  Individuals: 23

Unsatisfiable Classes: 0 ✅

Equivalent Classes:
  • MozzarellaTopping ≡ Mozzarella
  • AmericanPizza ≡ USAPizza

Inferred Hierarchy:
  owl:Thing
    ├─ Food
    │   ├─ Pizza [inferred from hasTopping domain]
    │   │   ├─ MargheritaPizza
    │   │   ├─ VegetarianPizza
    │   │   └─ MeatPizza
    │   └─ PizzaTopping
    │       ├─ CheeseTopping
    │       └─ MeatTopping
    └─ Country
```

## 🎓 Learn More

- **OWL 2 Reasoning**: https://www.w3.org/TR/owl2-primer/
- **HermiT Reasoner**: http://www.hermit-reasoner.com/
- **Protégé Documentation**: https://protegewiki.stanford.edu/

## 🚦 Status Messages

| Message | Meaning |
|---------|---------|
| "Classification completed with HermiT" | ✅ Success |
| "Auto-sync enabled" | 🔄 Will re-run on changes |
| "Reasoner stopped" | ⏹️ Stopped manually |
| "Please load an ontology first" | ⚠️ No ontology loaded |
| "Classification failed" | ❌ Error occurred |

---

**🎉 You're ready to use the reasoner!**

Start with: Reasoner → HermiT → Start reasoner → Hover for explanations
