import React, { useState, useEffect } from 'react';
import { Sparkles, Plus, Trash2, Save, Play, Download, Edit2, TrendingUp, Zap } from 'lucide-react';
import MembershipFunctionCanvas from './components/MembershipFunctionCanvas';

declare global {
  interface Window { API_BASE_URL?: string; }
}

function apiUrl(path: string) {
  const base = (window.API_BASE_URL || '').replace(/\/$/, '');
  return `${base}${path}`;
}

function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const token = localStorage.getItem('authToken');
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

// Fuzzy modifier functions

const FUZZY_MODIFIERS = {
  'extremely': (degree: number) => Math.pow(degree, 4),
  'very': (degree: number) => degree * degree,
  'slightly': (degree: number) => Math.pow(degree, 0.75),
  'more_or_less': (degree: number) => Math.sqrt(degree),
  'somewhat': (degree: number) => Math.pow(degree, 0.33),
};

// Membership function types
type MembershipFunctionType = 'singleton' | 'triangular' | 'trapezoidal' | 'gaussian' | 'sigmoid';

interface MembershipFunction {
  type: MembershipFunctionType;
  parameters: number[]; // varies by type
}

interface FuzzyMembership {
  id: string;
  entity: string;
  fuzzyClass: string;
  degree: number;
  modifier?: keyof typeof FUZZY_MODIFIERS; // very, more_or_less, etc.
  membershipFunction?: MembershipFunction;
  dataValue?: number; // for function evaluation
}

interface FuzzyRule {
  id: string;
  name: string;
  condition: string;
  action: string;
  enabled: boolean;
  tNorm?: 'min' | 'product' | 'lukasiewicz'; // T-norm for AND
  tConorm?: 'max' | 'probabilistic' | 'lukasiewicz'; // T-conorm for OR
}

interface FuzzyEditorEnhancedProps {
  projectId: string;
}

const FuzzyEditorEnhanced: React.FC<FuzzyEditorEnhancedProps> = ({ projectId }) => {
  const [memberships, setMemberships] = useState<FuzzyMembership[]>([]);
  const [rules, setRules] = useState<FuzzyRule[]>([]);
  const [activeTab, setActiveTab] = useState<'memberships' | 'functions' | 'modifiers' | 'rules' | 'query'>('memberships');
  
  // New membership form
  const [newEntity, setNewEntity] = useState('');
  const [newFuzzyClass, setNewFuzzyClass] = useState('');
  const [newDegree, setNewDegree] = useState(0.5);
  const [newModifier, setNewModifier] = useState<string>('none');
  const [newFunctionType, setNewFunctionType] = useState<MembershipFunctionType>('singleton');
  const [newFunctionParams, setNewFunctionParams] = useState<number[]>([]);
  const [newDataValue, setNewDataValue] = useState<number>(0);
  
  // Function editor state
  const [showFunctionEditor, setShowFunctionEditor] = useState(false);
  const [editingFunction, setEditingFunction] = useState<MembershipFunction | null>(null);
  
  // Editing state
  const [editingMembershipId, setEditingMembershipId] = useState<string | null>(null);
  
  // Success notification state
  const [showSuccessMessage, setShowSuccessMessage] = useState(false);
  const [successMessage, setSuccessMessage] = useState('');
  
  // New rule form
  const [newRuleName, setNewRuleName] = useState('');
  const [newCondition, setNewCondition] = useState('');
  const [newAction, setNewAction] = useState('');
  const [newTNorm, setNewTNorm] = useState<'min' | 'product' | 'lukasiewicz'>('min');
  const [newTConorm, setNewTConorm] = useState<'max' | 'probabilistic' | 'lukasiewicz'>('max');
  
  // Fuzzy query
  const [fuzzyQuery, setFuzzyQuery] = useState('');
  const [queryResults, setQueryResults] = useState<any[]>([]);
  const [queryError, setQueryError] = useState<string>('');


  useEffect(() => {
    // Clear state when switching projects
    setMemberships([]);
    setRules([]);
    setQueryResults([]);
    setQueryError('');
    
    // Load fuzzy data for the new project
    loadFuzzyData();
  }, [projectId]);

  // Escape string for SPARQL literal
  const escapeSparqlString = (str: string): string => {
    return str
      .replace(/\\/g, '\\\\')   // Escape backslashes first
      .replace(/"/g, '\\"')      // Escape quotes
      .replace(/\n/g, '\\n')     // Escape newlines
      .replace(/\r/g, '\\r')     // Escape carriage returns
      .replace(/\t/g, '\\t');    // Escape tabs
  };

  // Calculate effective degree with modifier
  const getEffectiveDegree = (membership: FuzzyMembership): number => {
    let degree = membership.degree;
    
    // Apply membership function if exists
    if (membership.membershipFunction && membership.dataValue !== undefined) {
      degree = evaluateMembershipFunction(
        membership.membershipFunction,
        membership.dataValue
      );
    }
    
    // Apply modifier
    if (membership.modifier) {
      const modifierFn = FUZZY_MODIFIERS[membership.modifier];
      if (modifierFn) {
        degree = modifierFn(degree);
      }
    }
    
    return Math.max(0, Math.min(1, degree));
  };

  // Evaluate membership function
  const evaluateMembershipFunction = (func: MembershipFunction, value: number): number => {
    switch (func.type) {
      case 'singleton':
        return func.parameters[0] || 0;
      
      case 'triangular': {
        const [a, b, c] = func.parameters;
        if (value <= a || value >= c) return 0;
        if (value === b) return 1;
        if (value < b) return (value - a) / (b - a);
        return (c - value) / (c - b);
      }
      
      case 'trapezoidal': {
        const [a, b, c, d] = func.parameters;
        if (value <= a || value >= d) return 0;
        if (value >= b && value <= c) return 1;
        if (value < b) return (value - a) / (b - a);
        return (d - value) / (d - c);
      }
      
      case 'gaussian': {
        const [mean, sigma] = func.parameters;
        return Math.exp(-Math.pow(value - mean, 2) / (2 * Math.pow(sigma, 2)));
      }
      
      case 'sigmoid': {
        const [slope, center] = func.parameters;
        return 1 / (1 + Math.exp(-slope * (value - center)));
      }
      
      default:
        return 0;
    }
  };

  const loadFuzzyData = async () => {
    try {
      // Load memberships
      const membershipResponse = await fetch(apiUrl(`/api/sparql/query/${projectId}`), {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({
          query: `
            PREFIX fuzzy: <http://fuzzy.org/ontology#>
            SELECT ?entity ?class ?degree ?modifier ?functionType ?functionParams ?dataValue
            WHERE {
              ?entity fuzzy:hasMembership ?membership .
              ?membership fuzzy:inClass ?class ;
                          fuzzy:degree ?degree .
              OPTIONAL { ?membership fuzzy:modifier ?modifier }
              OPTIONAL { ?membership fuzzy:functionType ?functionType }
              OPTIONAL { ?membership fuzzy:functionParams ?functionParams }
              OPTIONAL { ?membership fuzzy:dataValue ?dataValue }
            }
          `
        })
      });

      if (membershipResponse.ok) {
        const data = await membershipResponse.json();
        if (data.results && data.results.length > 0) {
          const loadedMemberships = data.results.map((row: any, index: number) => {
            const membership: FuzzyMembership = {
              id: `membership-${index}`,
              entity: row.entity || '',
              fuzzyClass: row.class || '',
              degree: parseFloat(row.degree || '0'),
            };
            
            if (row.modifier) membership.modifier = row.modifier as keyof typeof FUZZY_MODIFIERS;
            if (row.dataValue) membership.dataValue = parseFloat(row.dataValue);
            
            if (row.functionType && row.functionParams) {
              membership.membershipFunction = {
                type: row.functionType as MembershipFunctionType,
                parameters: JSON.parse(row.functionParams)
              };
            }
            
            return membership;
          });
          setMemberships(loadedMemberships);
          console.log(`✅ Loaded ${loadedMemberships.length} fuzzy memberships for project ${projectId}`);
        } else {
          setMemberships([]);
          console.log(`ℹ️ No fuzzy memberships found for project ${projectId}`);
        }
      }

      // Load rules
      const ruleResponse = await fetch(apiUrl(`/api/sparql/query/${projectId}`), {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({
          query: `
            PREFIX fuzzy: <http://fuzzy.org/ontology#>
            SELECT ?rule ?ruleName ?condition ?action ?enabled ?tNorm ?tConorm
            WHERE {
              ?rule a fuzzy:Rule ;
                    fuzzy:ruleName ?ruleName ;
                    fuzzy:condition ?condition ;
                    fuzzy:action ?action ;
                    fuzzy:enabled ?enabled .
              OPTIONAL { ?rule fuzzy:tNorm ?tNorm }
              OPTIONAL { ?rule fuzzy:tConorm ?tConorm }
            }
          `
        })
      });

      if (ruleResponse.ok) {
        const data = await ruleResponse.json();
        if (data.results && data.results.length > 0) {
          const loadedRules = data.results.map((row: any, index: number) => {
            const ruleId = row.rule.split('/').pop() || `rule-${index}`;
            return {
              id: ruleId,
              name: row.ruleName || '',
              condition: row.condition || '',
              action: row.action || '',
              enabled: row.enabled === 'true',
              tNorm: row.tNorm || 'min',
              tConorm: row.tConorm || 'max'
            };
          });
          setRules(loadedRules);
          console.log(`✅ Loaded ${loadedRules.length} fuzzy rules for project ${projectId}`);
        } else {
          setRules([]);
          console.log(`ℹ️ No fuzzy rules found for project ${projectId}`);
        }
      }
    } catch (error) {
      console.error('❌ Failed to load fuzzy data:', error);
    }
  };

  const addMembership = () => {
    if (!newEntity || !newFuzzyClass) {
      console.warn('⚠️ Please enter entity and fuzzy class');
      return;
    }

    const membership: FuzzyMembership = {
      id: `membership-${Date.now()}`,
      entity: newEntity,
      fuzzyClass: newFuzzyClass,
      degree: newDegree,
      modifier: newModifier !== 'none' ? (newModifier as keyof typeof FUZZY_MODIFIERS) : undefined,
    };
    
    // Add membership function if not singleton
    if (newFunctionType !== 'singleton' && newFunctionParams.length > 0) {
      membership.membershipFunction = {
        type: newFunctionType,
        parameters: newFunctionParams
      };
      membership.dataValue = newDataValue;
    }

    setMemberships([...memberships, membership]);
    
    // Reset form
    setNewEntity('');
    setNewFuzzyClass('');
    setNewDegree(0.5);
    setNewModifier('none');
    setNewFunctionType('singleton');
    setNewFunctionParams([]);
    setNewDataValue(0);
  };

  const deleteMembership = (id: string) => {
    setMemberships(memberships.filter(m => m.id !== id));
  };

  const startEditMembership = (membership: FuzzyMembership) => {
    setEditingMembershipId(membership.id);
    setNewEntity(membership.entity);
    setNewFuzzyClass(membership.fuzzyClass);
    setNewDegree(membership.degree);
    setNewModifier(membership.modifier || 'none');
    if (membership.membershipFunction) {
      setNewFunctionType(membership.membershipFunction.type);
      setNewFunctionParams(membership.membershipFunction.parameters);
    }
    if (membership.dataValue !== undefined) {
      setNewDataValue(membership.dataValue);
    }
  };

  const updateMembership = () => {
    if (!newEntity || !newFuzzyClass) {
      console.warn('⚠️ Please enter entity and fuzzy class');
      return;
    }

    setMemberships(memberships.map(m => {
      if (m.id === editingMembershipId) {
        return {
          ...m,
          entity: newEntity,
          fuzzyClass: newFuzzyClass,
          degree: newDegree,
          modifier: newModifier === 'none' ? undefined : newModifier as keyof typeof FUZZY_MODIFIERS,
          membershipFunction: newFunctionType !== 'singleton' && newFunctionParams.length > 0 ? {
            type: newFunctionType,
            parameters: newFunctionParams
          } : undefined,
          dataValue: newDataValue
        };
      }
      return m;
    }));

    // Reset form
    setEditingMembershipId(null);
    setNewEntity('');
    setNewFuzzyClass('');
    setNewDegree(0.5);
    setNewModifier('none');
    setNewFunctionParams([]);
    setNewDataValue(0);
  };

  const cancelEdit = () => {
    setEditingMembershipId(null);
    setNewEntity('');
    setNewFuzzyClass('');
    setNewDegree(0.5);
    setNewModifier('none');
    setNewFunctionParams([]);
    setNewDataValue(0);
  };

  const showSuccess = (message: string) => {
    setSuccessMessage(message);
    setShowSuccessMessage(true);
    setTimeout(() => setShowSuccessMessage(false), 3000);
  };

  const addRule = () => {
    if (!newRuleName || !newCondition || !newAction) {
      console.warn('⚠️ Please fill in all rule fields');
      return;
    }

    const rule: FuzzyRule = {
      id: `rule-${Date.now()}`,
      name: newRuleName,
      condition: newCondition,
      action: newAction,
      enabled: true,
      tNorm: newTNorm,
      tConorm: newTConorm
    };

    setRules([...rules, rule]);
    setNewRuleName('');
    setNewCondition('');
    setNewAction('');
  };

  const deleteRule = (id: string) => {
    setRules(rules.filter(r => r.id !== id));
  };

  const toggleRule = (id: string) => {
    setRules(rules.map(r => r.id === id ? { ...r, enabled: !r.enabled } : r));
  };

  const saveFuzzyOntology = async () => {
    try {
      // Check if there's anything to save
      if (memberships.length === 0 && rules.length === 0) {
        console.warn('⚠️ No fuzzy data to save. Add memberships or rules first.');
        return;
      }

      // Step 1: Delete all existing fuzzy data to prevent duplicates
      const deleteQuery = `
        PREFIX fuzzy: <http://fuzzy.org/ontology#>
        PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
        
        DELETE {
          ?entity fuzzy:hasMembership ?membership .
          ?membership ?p ?o .
          ?rule ?rp ?ro .
        }
        WHERE {
          {
            ?entity fuzzy:hasMembership ?membership .
            ?membership ?p ?o .
          }
          UNION
          {
            ?rule a fuzzy:Rule .
            ?rule ?rp ?ro .
          }
        }
      `;

      console.log('🗑️ Deleting existing fuzzy data...');
      
      const deleteResponse = await fetch(apiUrl(`/api/sparql/update/${projectId}`), {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({
          query: deleteQuery
        })
      });

      if (!deleteResponse.ok) {
        console.error('❌ Failed to delete existing fuzzy data');
        setQueryError('Failed to clear existing data before save. Try again.');
        return;
      }

      console.log('✅ Existing fuzzy data deleted');

      // Step 2: Insert new fuzzy data
      const insertStatements = memberships.map((m, idx) => {
        const effectiveDegree = getEffectiveDegree(m);
        const bnodeId = `_:m${idx}`;
        
        let statements = [];
        statements.push(`<${m.entity}> fuzzy:hasMembership ${bnodeId} .`);
        statements.push(`${bnodeId} fuzzy:inClass <${m.fuzzyClass}> .`);
        statements.push(`${bnodeId} fuzzy:degree "${effectiveDegree}"^^xsd:float .`);
        statements.push(`${bnodeId} fuzzy:originalDegree "${m.degree}"^^xsd:float .`);
        
        if (m.modifier) {
          statements.push(`${bnodeId} fuzzy:modifier "${m.modifier}" .`);
        }
        
        if (m.membershipFunction) {
          statements.push(`${bnodeId} fuzzy:functionType "${m.membershipFunction.type}" .`);
          const paramsJson = JSON.stringify(m.membershipFunction.parameters).replace(/"/g, '\\"');
          statements.push(`${bnodeId} fuzzy:functionParams "${paramsJson}" .`);
        }
        
        if (m.dataValue !== undefined) {
          statements.push(`${bnodeId} fuzzy:dataValue "${m.dataValue}"^^xsd:float .`);
        }
        
        return statements.join('\n          ');
      }).join('\n          ');

      const ruleStatements = rules.map(r => {
        let statements = [];
        const ruleIri = `<http://example.org/rules/${r.id}>`;
        statements.push(`${ruleIri} a fuzzy:Rule .`);
        statements.push(`${ruleIri} fuzzy:ruleName "${escapeSparqlString(r.name)}" .`);
        statements.push(`${ruleIri} fuzzy:condition "${escapeSparqlString(r.condition)}" .`);
        statements.push(`${ruleIri} fuzzy:action "${escapeSparqlString(r.action)}" .`);
        statements.push(`${ruleIri} fuzzy:enabled "${r.enabled}"^^xsd:boolean .`);
        statements.push(`${ruleIri} fuzzy:tNorm "${r.tNorm || 'min'}" .`);
        statements.push(`${ruleIri} fuzzy:tConorm "${r.tConorm || 'max'}" .`);
        return statements.join('\n          ');
      }).join('\n          ');

      // Combine statements, ensuring at least one exists
      const allStatements = [insertStatements, ruleStatements].filter(s => s.trim()).join('\n          ');

      const sparqlUpdate = `
        PREFIX fuzzy: <http://fuzzy.org/ontology#>
        PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
        
        INSERT DATA {
          ${allStatements}
        }
      `;

      console.log('📝 Inserting new fuzzy data...');

      const response = await fetch(apiUrl(`/api/sparql/update/${projectId}`), {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({
          query: sparqlUpdate
        })
      });

      if (response.ok) {
        showSuccess('Fuzzy ontology saved successfully!');
      } else {
        const errText = await response.text().catch(() => '');
        let msg = `Failed to save fuzzy ontology (${response.status})`;
        try { msg = JSON.parse(errText).error || msg; } catch { /* ignore */ }
        setQueryError(msg);
      }
    } catch (error) {
      setQueryError(`Save error: ${error}`);
    }
  };

  const executeFuzzyQuery = async () => {
    if (!fuzzyQuery.trim()) {
      console.warn('⚠️ Please enter a SPARQL query');
      return;
    }

    // Check for common copy-paste errors
    const trimmedQuery = fuzzyQuery.trim();
    if (trimmedQuery.startsWith('sparql') || trimmedQuery.startsWith('```')) {
      setQueryError('Invalid query: Remove "sparql" or "```" markdown markers. Query should start with PREFIX or SELECT.');
      console.error('⚠️ Query contains markdown code fence markers');
      return;
    }

    console.log('📝 Executing SPARQL Query:', fuzzyQuery);

    try {
      setQueryError(''); // Clear previous errors
      const response = await fetch(apiUrl(`/api/sparql/query/${projectId}`), {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({
          query: fuzzyQuery
        })
      });

      if (response.ok) {
        const data = await response.json();
        setQueryResults(data.results || []);
        console.log('✅ Query executed successfully', data);
      } else {
        const errorText = await response.text();
        console.error('❌ Query execution failed:', response.status, errorText);
        console.error('Query was:', fuzzyQuery);
        
        let errorMessage = `Query failed (${response.status})`;
        if (response.status === 404) {
          errorMessage += ': Project not found in GraphDB. Please create/upload an ontology first.';
        } else if (response.status === 400) {
          errorMessage += ': Invalid SPARQL syntax. Check your query.';
        } else if (response.status === 500) {
          errorMessage += ': Server error. Check if the project repository exists and the query is valid.';
        }
        
        setQueryError(errorMessage);
        setQueryResults([]);
      }
    } catch (error) {
      console.error('❌ Error executing query:', error);
      setQueryError(`Network error: ${error}. Make sure backend services are running.`);
      setQueryResults([]);
    }
  };

  const exportFuzzyData = () => {
    const turtle = `@prefix fuzzy: <http://fuzzy.org/ontology#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

# Fuzzy Memberships with Modifiers and Functions
${memberships.map(m => {
  const effectiveDegree = getEffectiveDegree(m);
  let ttl = `<${m.entity}> fuzzy:hasMembership [
    fuzzy:inClass <${m.fuzzyClass}> ;
    fuzzy:degree "${effectiveDegree}"^^xsd:float ;
    fuzzy:originalDegree "${m.degree}"^^xsd:float`;
  
  if (m.modifier) ttl += ` ;\n    fuzzy:modifier "${m.modifier}"`;
  if (m.membershipFunction) {
    ttl += ` ;\n    fuzzy:functionType "${m.membershipFunction.type}"`;
    ttl += ` ;\n    fuzzy:functionParams "${JSON.stringify(m.membershipFunction.parameters)}"`;
  }
  if (m.dataValue !== undefined) ttl += ` ;\n    fuzzy:dataValue "${m.dataValue}"^^xsd:float`;
  
  ttl += `\n] .`;
  return ttl;
}).join('\n\n')}

# Fuzzy Rules with T-norms
${rules.map(r => `<http://example.org/rules/${r.id}> a fuzzy:Rule ;
    fuzzy:ruleName "${r.name}" ;
    fuzzy:condition """${r.condition}""" ;
    fuzzy:action """${r.action}""" ;
    fuzzy:tNorm "${r.tNorm}" ;
    fuzzy:tConorm "${r.tConorm}" .`).join('\n\n')}`;

    const blob = new Blob([turtle], { type: 'text/turtle' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `fuzzy-ontology-${projectId}.ttl`;
    a.click();
    URL.revokeObjectURL(url);
  };

  // Render membership function editor
  const renderFunctionEditor = () => {
    if (!showFunctionEditor) return null;

    const paramLabels: { [key in MembershipFunctionType]: string[] } = {
      singleton: ['Degree'],
      triangular: ['Min (a)', 'Peak (b)', 'Max (c)'],
      trapezoidal: ['Min (a)', 'Start Peak (b)', 'End Peak (c)', 'Max (d)'],
      gaussian: ['Mean (μ)', 'Std Dev (σ)'],
      sigmoid: ['Slope', 'Center']
    };

    const labels = paramLabels[newFunctionType];
    const paramCount = labels.length;

    return (
      <div style={styles.modal}>
        <div style={styles.modalContent}>
          <h3>Membership Function Editor</h3>
          
          <label style={styles.label}>Function Type:</label>
          <select 
            value={newFunctionType} 
            onChange={(e) => {
              setNewFunctionType(e.target.value as MembershipFunctionType);
              setNewFunctionParams([]);
            }}
            style={styles.select}
          >
            <option value="singleton">Singleton (single degree)</option>
            <option value="triangular">Triangular (3 params)</option>
            <option value="trapezoidal">Trapezoidal (4 params)</option>
            <option value="gaussian">Gaussian (2 params)</option>
            <option value="sigmoid">Sigmoid (2 params)</option>
          </select>

          <div style={styles.paramGrid}>
            {labels.map((label, idx) => (
              <div key={idx}>
                <label style={styles.label}>{label}:</label>
                <input
                  type="number"
                  step="0.01"
                  value={newFunctionParams[idx] || ''}
                  onChange={(e) => {
                    const newParams = [...newFunctionParams];
                    newParams[idx] = parseFloat(e.target.value) || 0;
                    setNewFunctionParams(newParams);
                  }}
                  style={styles.input}
                />
              </div>
            ))}
          </div>

          {newFunctionType !== 'singleton' && (
            <>
              <label style={styles.label}>Data Value (for evaluation):</label>
              <input
                type="number"
                step="0.1"
                value={newDataValue}
                onChange={(e) => setNewDataValue(parseFloat(e.target.value) || 0)}
                style={styles.input}
              />
              
              {newFunctionParams.length === paramCount && (
                <>
                  <div style={styles.preview}>
                    <strong>Computed Degree: </strong>
                    {evaluateMembershipFunction(
                      { type: newFunctionType, parameters: newFunctionParams },
                      newDataValue
                    ).toFixed(3)}
                  </div>
                  
                  <div style={{ marginTop: '16px' }}>
                    <MembershipFunctionCanvas
                      membershipFunction={{ type: newFunctionType, parameters: newFunctionParams }}
                      width={500}
                      height={250}
                      domain={[0, 100]}
                    />
                  </div>
                </>
              )}
            </>
          )}

          <div style={styles.modalActions}>
            <button onClick={() => setShowFunctionEditor(false)} style={styles.button}>
              Apply
            </button>
            <button 
              onClick={() => {
                setShowFunctionEditor(false);
                setNewFunctionParams([]);
              }} 
              style={{...styles.button, ...styles.secondaryButton}}
            >
              Cancel
            </button>
          </div>
        </div>
      </div>
    );
  };

  return (
    <div style={styles.container}>
      {showSuccessMessage && (
        <div style={styles.successMessage}>
          ✅ {successMessage}
        </div>
      )}
      
      <div style={styles.header}>
        <div style={styles.headerLeft}>
          <Sparkles size={24} color="#a855f7" />
          <h2 style={styles.title}>Fuzzy Ontology Editor</h2>
        </div>
        <div style={styles.headerRight}>
          <button onClick={saveFuzzyOntology} style={{...styles.button, ...styles.primaryButton}}>
            <Save size={16} />
            Save to Ontology
          </button>
          <button onClick={exportFuzzyData} style={{...styles.button, ...styles.successButton}}>
            <Download size={16} />
            Export Turtle
          </button>
        </div>
      </div>

      <div style={styles.tabs}>
        <button
          style={activeTab === 'memberships' ? {...styles.tab, ...styles.activeTab} : styles.tab}
          onClick={() => setActiveTab('memberships')}
        >
          Fuzzy Memberships
        </button>
        <button
          style={activeTab === 'functions' ? {...styles.tab, ...styles.activeTab} : styles.tab}
          onClick={() => setActiveTab('functions')}
        >
          <TrendingUp size={16} />
          Membership Functions
        </button>
        <button
          style={activeTab === 'modifiers' ? {...styles.tab, ...styles.activeTab} : styles.tab}
          onClick={() => setActiveTab('modifiers')}
        >
          <Zap size={16} />
          Fuzzy Modifiers
        </button>
        <button
          style={activeTab === 'rules' ? {...styles.tab, ...styles.activeTab} : styles.tab}
          onClick={() => setActiveTab('rules')}
        >
          Fuzzy Rules
        </button>
        <button
          style={activeTab === 'query' ? {...styles.tab, ...styles.activeTab} : styles.tab}
          onClick={() => setActiveTab('query')}
        >
          Query Builder
        </button>
      </div>

      {activeTab === 'memberships' && (
        <div style={styles.tabContent}>
          <div style={styles.addSection}>
            <h3 style={styles.sectionTitle}>
              <Plus size={20} />
              Add Fuzzy Membership
            </h3>
            
            <div style={styles.formGrid}>
              <div>
                <label style={styles.label}>Entity IRI (e.g., :patient1):</label>
                <input
                  type="text"
                  value={newEntity}
                  onChange={(e) => setNewEntity(e.target.value)}
                  placeholder="http://example.org/medical#Patient001"
                  style={styles.input}
                />
              </div>

              <div>
                <label style={styles.label}>Fuzzy Class (e.g., :Diabetic):</label>
                <input
                  type="text"
                  value={newFuzzyClass}
                  onChange={(e) => setNewFuzzyClass(e.target.value)}
                  placeholder="http://example.org/medical#Diabetic"
                  style={styles.input}
                />
              </div>

              <div>
                <label style={styles.label}>Base Degree: {newDegree.toFixed(2)}</label>
                <input
                  type="range"
                  min="0"
                  max="1"
                  step="0.01"
                  value={newDegree}
                  onChange={(e) => setNewDegree(parseFloat(e.target.value))}
                  style={styles.slider}
                />
              </div>

              <div>
                <label style={styles.label}>Fuzzy Modifier:</label>
                <select 
                  value={newModifier} 
                  onChange={(e) => setNewModifier(e.target.value)}
                  style={styles.select}
                >
                  <option value="none">None</option>
                  <option value="extremely">extremely (x⁴) - much much stronger membership</option>
                  <option value="very">very (x²) - much stronger membership</option>
                  <option value="slightly">slightly (x^0.75) - slightly weaker membership</option>
                  <option value="more_or_less">more or less (√x) - weaker membership</option>
                  <option value="somewhat">somewhat (x^0.33) - much weaker membership</option>
                </select>
              </div>

              <div>
                <label style={styles.label}>Membership Function:</label>
                <button 
                  onClick={() => setShowFunctionEditor(true)}
                  style={{...styles.button, ...styles.infoButton}}
                >
                  <TrendingUp size={16} />
                  Configure Function
                </button>
              </div>

              <div style={styles.addButtonContainer}>
                {editingMembershipId ? (
                  <>
                    <button onClick={updateMembership} style={{...styles.button, ...styles.addButton}}>
                      <Save size={20} />
                      Save Changes
                    </button>
                    <button onClick={cancelEdit} style={{...styles.button, ...styles.deleteButton}}>
                      Cancel
                    </button>
                  </>
                ) : (
                  <button onClick={addMembership} style={{...styles.button, ...styles.addButton}}>
                    <Plus size={20} />
                    Add
                  </button>
                )}
              </div>
            </div>

            {newModifier !== 'none' && (
              <div style={styles.modifierPreview}>
                <strong>Effective Degree:</strong> {
                  FUZZY_MODIFIERS[newModifier as keyof typeof FUZZY_MODIFIERS](newDegree).toFixed(3)
                } 
                <span style={styles.hint}> (base: {newDegree.toFixed(2)} → {newModifier})</span>
              </div>
            )}
          </div>

          <div style={styles.list}>
            <h3 style={styles.sectionTitle}>
              Fuzzy Memberships ({memberships.length})
            </h3>
            {memberships.length === 0 ? (
              <p style={styles.emptyState}>No fuzzy memberships yet. Add one above!</p>
            ) : (
              <div style={styles.membershipGrid}>
                {memberships.map(m => {
                  const effectiveDegree = getEffectiveDegree(m);
                  return (
                    <div key={m.id} style={styles.membershipCard}>
                      <div style={styles.membershipHeader}>
                        <div>
                          <div style={styles.membershipEntity}>
                            {m.entity.split('#').pop() || m.entity.split('/').pop() || m.entity}
                          </div>
                          <div style={styles.membershipClass}>
                            → {m.fuzzyClass.split('#').pop() || m.fuzzyClass.split('/').pop() || m.fuzzyClass}
                            {m.modifier && <span style={styles.modifierBadge}>{m.modifier}</span>}
                          </div>
                        </div>
                        <div style={{display: 'flex', gap: '8px'}}>
                          <button
                            onClick={() => startEditMembership(m)}
                            style={{...styles.iconButton, ...styles.editButton}}
                            title="Edit membership"
                          >
                            <Edit2 size={16} />
                          </button>
                          <button
                            onClick={() => deleteMembership(m.id)}
                            style={{...styles.iconButton, ...styles.deleteButton}}
                            title="Delete membership"
                          >
                            <Trash2 size={16} />
                          </button>
                        </div>
                      </div>
                      
                      <div style={styles.degreeBar}>
                        <div 
                          style={{
                            ...styles.degreeBarFill,
                            width: `${effectiveDegree * 100}%`,
                            backgroundColor: effectiveDegree > 0.7 ? '#10b981' : effectiveDegree > 0.4 ? '#f59e0b' : '#ef4444'
                          }}
                        />
                      </div>
                      
                      <div style={styles.membershipFooter}>
                        <span>Degree: <strong>{effectiveDegree.toFixed(3)}</strong></span>
                        {m.modifier && <span style={styles.hint}>(base: {m.degree.toFixed(2)})</span>}
                        {m.membershipFunction && (
                          <span style={styles.functionBadge}>
                            {m.membershipFunction.type}
                          </span>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      )}

      {activeTab === 'functions' && (
        <div style={styles.tabContent}>
          <h3 style={styles.sectionTitle}>Membership Function Types</h3>
          <div style={styles.functionTypes}>
            <div style={styles.functionCard}>
              <h4>Singleton</h4>
              <p>Single fixed degree value</p>
              <code>μ(x) = c</code>
            </div>
            <div style={styles.functionCard}>
              <h4>Triangular</h4>
              <p>Linear ramp up and down</p>
              <code>μ(x) = max(min((x-a)/(b-a), (c-x)/(c-b)), 0)</code>
            </div>
            <div style={styles.functionCard}>
              <h4>Trapezoidal</h4>
              <p>Flat top with linear sides</p>
              <code>μ(x) = trapezoid(a,b,c,d)</code>
            </div>
            <div style={styles.functionCard}>
              <h4>Gaussian</h4>
              <p>Bell curve distribution</p>
              <code>μ(x) = exp(-(x-μ)²/(2σ²))</code>
            </div>
            <div style={styles.functionCard}>
              <h4>Sigmoid</h4>
              <p>S-shaped curve</p>
              <code>μ(x) = 1/(1+exp(-k(x-c)))</code>
            </div>
          </div>
        </div>
      )}

      {activeTab === 'modifiers' && (
        <div style={styles.tabContent}>
          <h3 style={styles.sectionTitle}>Fuzzy Modifiers (Hedges)</h3>
          <div style={styles.modifiersList}>
            {Object.entries(FUZZY_MODIFIERS).map(([name, fn]) => (
              <div key={name} style={styles.modifierCard}>
                <h4 style={styles.modifierName}>{name.replace('_', ' ')}</h4>
                <div style={styles.modifierFormula}>
                  {name === 'extremely' && 'μ′(x) = μ(x)⁴ (much much stronger membership)'}
                  {name === 'very' && 'μ′(x) = μ(x)² (much stronger membership)'}
                  {name === 'slightly' && 'μ′(x) = μ(x)^0.75 (slightly weaker membership)'}
                  {name === 'more_or_less' && 'μ′(x) = √μ(x) (weaker membership)'}
                  {name === 'somewhat' && 'μ′(x) = μ(x)^0.33 (much weaker membership)'}
                </div>
                <div style={styles.modifierExample}>
                  <strong>Example:</strong>
                  <div>0.80 → {fn(0.8).toFixed(3)}</div>
                  <div>0.50 → {fn(0.5).toFixed(3)}</div>
                  <div>0.30 → {fn(0.3).toFixed(3)}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {activeTab === 'rules' && (
        <div style={styles.tabContent}>
          <div style={styles.addSection}>
            <h3 style={styles.sectionTitle}>
              <Plus size={20} />
              Add Fuzzy Rule
            </h3>
            
            <div>
              <label style={styles.label}>Rule Name:</label>
              <input
                type="text"
                value={newRuleName}
                onChange={(e) => setNewRuleName(e.target.value)}
                placeholder="HighRiskMonitoring"
                style={styles.input}
              />
            </div>

            <div>
              <label style={styles.label}>Condition (SPARQL-like):</label>
              <textarea
                value={newCondition}
                onChange={(e) => setNewCondition(e.target.value)}
                placeholder="?patient fuzzy:hasMembership ?m AND ?m fuzzy:degree > 0.7"
                rows={4}
                style={styles.textarea}
              />
            </div>

            <div>
              <label style={styles.label}>Action:</label>
              <textarea
                value={newAction}
                onChange={(e) => setNewAction(e.target.value)}
                placeholder=":requiresMonitoring(?patient, true)"
                rows={2}
                style={styles.textarea}
              />
            </div>

            <div style={styles.formGrid}>
              <div>
                <label style={styles.label}>T-Norm (AND operation):</label>
                <select 
                  value={newTNorm} 
                  onChange={(e) => setNewTNorm(e.target.value as any)}
                  style={styles.select}
                >
                  <option value="min">Minimum (Zadeh)</option>
                  <option value="product">Product</option>
                  <option value="lukasiewicz">Łukasiewicz</option>
                </select>
              </div>

              <div>
                <label style={styles.label}>T-Conorm (OR operation):</label>
                <select 
                  value={newTConorm} 
                  onChange={(e) => setNewTConorm(e.target.value as any)}
                  style={styles.select}
                >
                  <option value="max">Maximum (Zadeh)</option>
                  <option value="probabilistic">Probabilistic Sum</option>
                  <option value="lukasiewicz">Łukasiewicz</option>
                </select>
              </div>
            </div>

            <button onClick={addRule} style={{...styles.button, ...styles.addButton}}>
              <Plus size={20} />
              Add Rule
            </button>
          </div>

          <div style={styles.list}>
            <h3 style={styles.sectionTitle}>Fuzzy Rules ({rules.length})</h3>
            {rules.length === 0 ? (
              <p style={styles.emptyState}>No rules defined yet.</p>
            ) : (
              <div>
                {rules.map(r => (
                  <div key={r.id} style={styles.ruleCard}>
                    <div style={styles.ruleHeader}>
                      <div>
                        <span style={styles.ruleName}>{r.name}</span>
                        <span style={styles.ruleNorms}>
                          T-norm: {r.tNorm} | T-conorm: {r.tConorm}
                        </span>
                      </div>
                      <div style={styles.ruleActions}>
                        <button
                          onClick={() => toggleRule(r.id)}
                          style={{
                            ...styles.iconButton,
                            backgroundColor: r.enabled ? '#10b981' : '#6b7280'
                          }}
                        >
                          {r.enabled ? 'Enabled' : 'Disabled'}
                        </button>
                        <button
                          onClick={() => deleteRule(r.id)}
                          style={{...styles.iconButton, ...styles.deleteButton}}
                        >
                          <Trash2 size={16} />
                        </button>
                      </div>
                    </div>
                    <div style={styles.ruleBody}>
                      <div><strong>IF:</strong> {r.condition}</div>
                      <div><strong>THEN:</strong> {r.action}</div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {activeTab === 'query' && (
        <div style={styles.tabContent}>
          <h3 style={styles.sectionTitle}>
            <Play size={20} />
            Fuzzy SPARQL Query Builder
          </h3>

          <div>
            <label style={styles.label}>SPARQL Query with Fuzzy Extensions:</label>
            <textarea
              value={fuzzyQuery}
              onChange={(e) => setFuzzyQuery(e.target.value)}
              rows={12}
              placeholder={`PREFIX fuzzy: <http://fuzzy.org/ontology#>
PREFIX : <http://example.org/medical#>

SELECT ?patient ?class ?degree
WHERE {
  ?patient fuzzy:hasMembership ?membership .
  ?membership fuzzy:inClass ?class ;
              fuzzy:degree ?degree .
  FILTER(?degree > 0.7)
}
ORDER BY DESC(?degree)`}
              style={styles.textarea}
            />
          </div>

          <button onClick={executeFuzzyQuery} style={{...styles.button, ...styles.primaryButton}}>
            <Play size={16} />
            Execute Query
          </button>

          {queryError && (
            <div style={{
              padding: '12px',
              marginTop: '12px',
              backgroundColor: '#ff4444',
              color: 'white',
              borderRadius: '4px',
              fontSize: '14px'
            }}>
              ⚠️ {queryError}
            </div>
          )}

          {queryResults.length > 0 && (
            <div style={styles.results}>
              <h4>Results ({queryResults.length})</h4>
              <div style={styles.resultsTable}>
                <table style={styles.table}>
                  <thead>
                    <tr>
                      {Object.keys(queryResults[0] || {}).map(key => (
                        <th key={key} style={styles.th}>{key}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {queryResults.map((row, idx) => (
                      <tr key={idx}>
                        {Object.values(row).map((val: any, i) => (
                          <td key={i} style={styles.td}>{String(val)}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      )}

      {renderFunctionEditor()}
    </div>
  );
};

const styles: { [key: string]: React.CSSProperties } = {
  container: {
    padding: '20px',
    maxWidth: '1400px',
    margin: '0 auto',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
    backgroundColor: 'var(--surface-1)',
    color: 'var(--text-primary)',
    minHeight: '100vh',
    position: 'relative' as 'relative',
  },
  successMessage: {
    position: 'fixed' as 'fixed',
    top: '20px',
    right: '20px',
    backgroundColor: 'var(--success)',
    color: 'var(--text-primary)',
    padding: '16px 24px',
    borderRadius: '8px',
    boxShadow: '0 4px 12px rgba(0,0,0,0.3)',
    zIndex: 1000,
    fontSize: '14px',
    fontWeight: '500',
    animation: 'slideInRight 0.3s ease-out',
  },
  header: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '24px',
    paddingBottom: '16px',
    borderBottom: '2px solid var(--accent)',
  },
  headerLeft: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
  },
  headerRight: {
    display: 'flex',
    gap: '12px',
  },
  title: {
    margin: 0,
    fontSize: '24px',
    color: 'var(--text-primary)',
  },
  tabs: {
    display: 'flex',
    gap: '8px',
    marginBottom: '24px',
    borderBottom: '1px solid var(--border)',
  },
  tab: {
    padding: '12px 20px',
    border: 'none',
    background: 'transparent',
    color: 'var(--text-tertiary)',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500',
    borderBottom: '2px solid transparent',
    transition: 'all 0.2s',
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
  },
  activeTab: {
    color: 'var(--accent)',
    borderBottomColor: 'var(--accent)',
  },
  tabContent: {
    animation: 'fadeIn 0.3s',
  },
  addSection: {
    backgroundColor: 'var(--surface-2)',
    padding: '24px',
    borderRadius: '8px',
    marginBottom: '24px',
    border: '1px solid var(--border)',
  },
  sectionTitle: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    marginBottom: '16px',
    fontSize: '18px',
    color: 'var(--text-primary)',
  },
  formGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))',
    gap: '16px',
    marginBottom: '16px',
  },
  label: {
    display: 'block',
    marginBottom: '6px',
    fontSize: '13px',
    fontWeight: '500',
    color: 'var(--text-tertiary)',
  },
  input: {
    width: '100%',
    padding: '10px 12px',
    border: '1px solid var(--border)',
    borderRadius: '6px',
    fontSize: '14px',
    backgroundColor: 'var(--surface-1)',
    color: 'var(--text-primary)',
    boxSizing: 'border-box',
  },
  select: {
    width: '100%',
    padding: '10px 12px',
    border: '1px solid var(--border)',
    borderRadius: '6px',
    fontSize: '14px',
    backgroundColor: 'var(--surface-1)',
    color: 'var(--text-primary)',
    cursor: 'pointer',
  },
  textarea: {
    width: '100%',
    padding: '10px 12px',
    border: '1px solid var(--border)',
    borderRadius: '6px',
    fontSize: '13px',
    fontFamily: 'monospace',
    backgroundColor: 'var(--surface-1)',
    color: 'var(--text-primary)',
    resize: 'vertical',
    boxSizing: 'border-box',
  },
  slider: {
    width: '100%',
    accentColor: 'var(--accent)',
  },
  button: {
    padding: '10px 16px',
    border: 'none',
    borderRadius: '6px',
    fontSize: '14px',
    fontWeight: '500',
    cursor: 'pointer',
    display: 'inline-flex',
    alignItems: 'center',
    gap: '6px',
    transition: 'all 0.2s',
  },
  primaryButton: {
    backgroundColor: 'var(--color-accent)',
    color: 'var(--text-primary)',
  },
  successButton: {
    backgroundColor: 'var(--success)',
    color: 'var(--text-primary)',
  },
  addButton: {
    backgroundColor: 'var(--accent)',
    color: 'var(--text-primary)',
    width: '100%',
  },
  infoButton: {
    backgroundColor: 'var(--color-accent)',
    color: 'var(--text-primary)',
    width: '100%',
  },
  secondaryButton: {
    backgroundColor: 'var(--surface-2)',
    color: 'var(--text-primary)',
  },
  iconButton: {
    padding: '8px 12px',
    border: 'none',
    borderRadius: '6px',
    fontSize: '12px',
    cursor: 'pointer',
    backgroundColor: 'var(--border)',
    color: 'var(--text-primary)',
  },
  editButton: {
    backgroundColor: 'var(--color-accent)',
    color: 'var(--text-primary)',
  },
  deleteButton: {
    backgroundColor: 'var(--error)',
  },
  addButtonContainer: {
    display: 'flex',
    gap: '12px',
    alignItems: 'flex-end',
  },
  list: {
    backgroundColor: 'var(--surface-2)',
    padding: '24px',
    borderRadius: '8px',
    border: '1px solid var(--border)',
  },
  membershipGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))',
    gap: '16px',
  },
  membershipCard: {
    backgroundColor: 'var(--surface-1)',
    padding: '16px',
    borderRadius: '8px',
    border: '1px solid var(--border)',
  },
  membershipHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: '12px',
  },
  membershipEntity: {
    fontSize: '14px',
    fontWeight: '600',
    color: 'var(--text-primary)',
    marginBottom: '4px',
  },
  membershipClass: {
    fontSize: '13px',
    color: 'var(--text-tertiary)',
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
  },
  modifierBadge: {
    backgroundColor: 'var(--accent)',
    color: 'var(--text-primary)',
    padding: '2px 8px',
    borderRadius: '4px',
    fontSize: '11px',
    fontWeight: '600',
  },
  functionBadge: {
    backgroundColor: 'var(--color-accent)',
    color: 'var(--text-primary)',
    padding: '2px 8px',
    borderRadius: '4px',
    fontSize: '11px',
    fontWeight: '600',
  },
  degreeBar: {
    height: '8px',
    backgroundColor: 'var(--border)',
    borderRadius: '4px',
    overflow: 'hidden',
    marginBottom: '12px',
  },
  degreeBarFill: {
    height: '100%',
    transition: 'width 0.3s',
  },
  membershipFooter: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    fontSize: '12px',
    color: 'var(--text-tertiary)',
  },
  hint: {
    fontSize: '11px',
    color: 'var(--text-secondary)',
    fontStyle: 'italic',
  },
  modifierPreview: {
    marginTop: '16px',
    padding: '12px',
    backgroundColor: 'var(--surface-1)',
    borderRadius: '6px',
    border: '1px solid var(--accent)',
    fontSize: '14px',
  },
  emptyState: {
    textAlign: 'center',
    color: 'var(--text-secondary)',
    padding: '40px',
    fontSize: '14px',
  },
  functionTypes: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))',
    gap: '16px',
  },
  functionCard: {
    backgroundColor: 'var(--surface-2)',
    padding: '20px',
    borderRadius: '8px',
    border: '1px solid var(--border)',
  },
  modifiersList: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))',
    gap: '16px',
  },
  modifierCard: {
    backgroundColor: 'var(--surface-2)',
    padding: '20px',
    borderRadius: '8px',
    border: '1px solid var(--accent)',
  },
  modifierName: {
    fontSize: '16px',
    fontWeight: '600',
    color: 'var(--accent)',
    marginBottom: '8px',
    textTransform: 'capitalize',
  },
  modifierFormula: {
    fontFamily: 'monospace',
    fontSize: '13px',
    color: 'var(--text-tertiary)',
    marginBottom: '12px',
  },
  modifierExample: {
    fontSize: '12px',
    color: 'var(--text-secondary)',
  },
  ruleCard: {
    backgroundColor: 'var(--surface-1)',
    padding: '16px',
    borderRadius: '8px',
    border: '1px solid var(--border)',
    marginBottom: '12px',
  },
  ruleHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '12px',
  },
  ruleName: {
    fontSize: '16px',
    fontWeight: '600',
    color: 'var(--text-primary)',
    display: 'block',
  },
  ruleNorms: {
    fontSize: '11px',
    color: 'var(--text-secondary)',
    display: 'block',
    marginTop: '4px',
  },
  ruleActions: {
    display: 'flex',
    gap: '8px',
  },
  ruleBody: {
    fontSize: '13px',
    color: 'var(--text-tertiary)',
    fontFamily: 'monospace',
    lineHeight: '1.6',
  },
  results: {
    marginTop: '24px',
  },
  resultsTable: {
    overflowX: 'auto',
    marginTop: '12px',
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse',
    backgroundColor: 'var(--surface-2)',
  },
  th: {
    padding: '12px',
    textAlign: 'left',
    borderBottom: '2px solid var(--border)',
    fontSize: '13px',
    fontWeight: '600',
    color: 'var(--accent)',
  },
  td: {
    padding: '12px',
    borderBottom: '1px solid var(--border)',
    fontSize: '13px',
    color: 'var(--text-primary)',
  },
  modal: {
    position: 'fixed',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.7)',
    display: 'flex',
    alignItems: 'flex-start',
    justifyContent: 'center',
    zIndex: 1000,
    overflowY: 'auto',
    padding: '16px',
  },
  modalContent: {
    backgroundColor: 'var(--surface-2)',
    padding: '24px',
    borderRadius: '12px',
    maxWidth: '600px',
    width: '100%',
    border: '1px solid var(--border)',
    margin: 'auto',
  },
  modalActions: {
    display: 'flex',
    gap: '12px',
    marginTop: '20px',
    justifyContent: 'flex-end',
  },
  paramGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(2, 1fr)',
    gap: '12px',
    marginTop: '16px',
  },
  preview: {
    marginTop: '16px',
    padding: '12px',
    backgroundColor: 'var(--surface-1)',
    borderRadius: '6px',
    border: '1px solid var(--success)',
    color: 'var(--success)',
    fontSize: '14px',
  },
};

export default FuzzyEditorEnhanced;
