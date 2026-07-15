# Fix for Slow GraphDB Import (230MB file taking 1+ hour)

## Problem
GraphDB was using `owl-horst-optimized` inference ruleset, which computes millions of inferred triples during import. This causes:
- 500,000+ operations
- 1+ hour processing time for 230MB files
- High memory/CPU usage

## Solution: Disable Inference

### Option 1: Quick Recreation (RECOMMENDED)
1. Stop the current stuck import:
   - Access http://localhost:7200
   - Go to "Monitor" → "Active Queries"
   - Kill the running query
   
2. Run the recreation script:
   ```bash
   cd scripts
   .\recreate-repo-no-inference.bat
   ```

### Option 2: Manual Recreation
1. Access GraphDB at http://localhost:7200
2. Delete the "ontocode" repository
3. Create new repository:
   - Name: ontocode
   - Ruleset: **empty** (no inference)
   - Click Create

### Expected Performance After Fix
- **Before**: 230MB = 1+ hour (500,000+ operations)
- **After**: 230MB = 2-5 minutes (single transaction)

## Inference Options
If you need inference later, you can:
1. Keep imports fast with `empty` ruleset
2. Run SPARQL queries with inference on-demand
3. Or use `rdfs` ruleset (lighter than OWL)

## Available Rulesets
- `empty` - No inference (FASTEST for imports)
- `rdfs` - RDFS reasoning only (moderate)
- `owl-horst-optimized` - OWL reasoning (VERY SLOW)
- `owl2-rl` - Full OWL 2 RL (EXTREMELY SLOW)
