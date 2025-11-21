import React, { useState } from 'react';
import { Check } from 'lucide-react';

interface DatatypeDefinitionDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (expression: string, type: 'builtin' | 'expression') => void;
}

// Standard XSD built-in datatypes
const XSD_DATATYPES = [
  { name: 'xsd:decimal', description: 'Arbitrary-precision decimal numbers' },
  { name: 'xsd:integer', description: 'Arbitrary-size integer numbers' },
  { name: 'xsd:long', description: '64-bit signed integers' },
  { name: 'xsd:int', description: '32-bit signed integers' },
  { name: 'xsd:short', description: '16-bit signed integers' },
  { name: 'xsd:byte', description: '8-bit signed integers' },
  { name: 'xsd:nonNegativeInteger', description: 'Integers >= 0' },
  { name: 'xsd:positiveInteger', description: 'Integers > 0' },
  { name: 'xsd:unsignedLong', description: '64-bit unsigned integers' },
  { name: 'xsd:unsignedInt', description: '32-bit unsigned integers' },
  { name: 'xsd:unsignedShort', description: '16-bit unsigned integers' },
  { name: 'xsd:unsignedByte', description: '8-bit unsigned integers' },
  { name: 'xsd:nonPositiveInteger', description: 'Integers <= 0' },
  { name: 'xsd:negativeInteger', description: 'Integers < 0' },
  { name: 'xsd:double', description: '64-bit floating point' },
  { name: 'xsd:float', description: '32-bit floating point' },
  { name: 'xsd:boolean', description: 'true or false' },
  { name: 'xsd:string', description: 'Character strings' },
  { name: 'xsd:normalizedString', description: 'Whitespace-normalized strings' },
  { name: 'xsd:token', description: 'Tokenized strings' },
  { name: 'xsd:language', description: 'Language identifiers (RFC 3066)' },
  { name: 'xsd:Name', description: 'XML Names' },
  { name: 'xsd:NCName', description: 'XML NCNames (no colons)' },
  { name: 'xsd:NMTOKEN', description: 'XML name tokens' },
  { name: 'xsd:anyURI', description: 'URIs' },
  { name: 'xsd:dateTime', description: 'Date and time' },
  { name: 'xsd:dateTimeStamp', description: 'Date and time with timezone' },
  { name: 'xsd:date', description: 'Calendar dates' },
  { name: 'xsd:time', description: 'Time of day' },
  { name: 'xsd:duration', description: 'Duration of time' },
  { name: 'xsd:hexBinary', description: 'Hex-encoded binary data' },
  { name: 'xsd:base64Binary', description: 'Base64-encoded binary data' },
  { name: 'xsd:gYear', description: 'Gregorian calendar year' },
  { name: 'xsd:gYearMonth', description: 'Gregorian calendar year and month' },
  { name: 'xsd:gMonth', description: 'Gregorian calendar month' },
  { name: 'xsd:gMonthDay', description: 'Gregorian calendar month and day' },
  { name: 'xsd:gDay', description: 'Gregorian calendar day' },
];

// OWL built-in datatypes
const OWL_DATATYPES = [
  { name: 'owl:rational', description: 'Rational numbers' },
  { name: 'owl:real', description: 'Real numbers' },
];

// RDF/RDFS datatypes
const RDF_DATATYPES = [
  { name: 'rdf:langString', description: 'Language-tagged strings' },
  { name: 'rdf:PlainLiteral', description: 'Plain literals' },
  { name: 'rdf:XMLLiteral', description: 'XML literal values' },
  { name: 'rdfs:Literal', description: 'All literal values' },
];

const ALL_BUILTIN_DATATYPES = [
  ...OWL_DATATYPES,
  ...RDF_DATATYPES,
  ...XSD_DATATYPES
];

const DatatypeDefinitionDialog: React.FC<DatatypeDefinitionDialogProps> = ({
  isOpen,
  onClose,
  onConfirm
}) => {
  const [activeTab, setActiveTab] = useState<'builtin' | 'expression'>('builtin');
  const [selectedBuiltin, setSelectedBuiltin] = useState<string>('');
  const [searchQuery, setSearchQuery] = useState('');
  const [expression, setExpression] = useState('');

  if (!isOpen) return null;

  const handleConfirm = () => {
    if (activeTab === 'builtin' && selectedBuiltin) {
      onConfirm(selectedBuiltin, 'builtin');
      handleClose();
    } else if (activeTab === 'expression' && expression.trim()) {
      onConfirm(expression.trim(), 'expression');
      handleClose();
    }
  };

  const handleClose = () => {
    setSelectedBuiltin('');
    setExpression('');
    setSearchQuery('');
    setActiveTab('builtin');
    onClose();
  };

  const filteredDatatypes = ALL_BUILTIN_DATATYPES.filter(dt =>
    dt.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    dt.description.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const canConfirm = (activeTab === 'builtin' && selectedBuiltin) ||
                     (activeTab === 'expression' && expression.trim());

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={handleClose}>
      <div className="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 flex flex-col max-h-[80vh]" onClick={e => e.stopPropagation()}>
        {/* Header */}
        <div className="p-4 border-b border-gray-200 flex-shrink-0">
          <h3 className="text-lg font-semibold text-black">Add Datatype Definition</h3>
          <p className="text-xs text-gray-500 mt-1">
            Select a built-in XSD datatype or define a custom data range expression
          </p>
        </div>

        {/* Tabs */}
        <div className="flex border-b border-gray-200 bg-gray-50 flex-shrink-0">
          <button
            onClick={() => setActiveTab('builtin')}
            className={`flex-1 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'builtin'
                ? 'border-red-600 text-red-700 bg-white'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
            }`}
          >
            Built in datatypes
          </button>
          <button
            onClick={() => setActiveTab('expression')}
            className={`flex-1 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'expression'
                ? 'border-red-600 text-red-700 bg-white'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-100'
            }`}
          >
            Data range expression
          </button>
        </div>

        {/* Tab Content */}
        <div className="flex-1 overflow-hidden flex flex-col min-h-0">
          {activeTab === 'builtin' && (
            <div className="flex flex-col h-full">
              {/* Search */}
              <div className="p-3 border-b border-gray-200 flex-shrink-0">
                <input
                  type="text"
                  placeholder="Search datatypes..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full px-3 py-2 text-sm border border-gray-300 rounded-md focus:ring-red-500 focus:border-red-500"
                />
              </div>

              {/* Datatype List */}
              <div className="flex-1 overflow-y-auto p-3 space-y-1">
                {filteredDatatypes.map((dt) => (
                  <button
                    key={dt.name}
                    onClick={() => setSelectedBuiltin(dt.name)}
                    className={`w-full text-left p-2.5 rounded-md transition-colors flex items-start gap-2 ${
                      selectedBuiltin === dt.name
                        ? 'bg-red-50 border-2 border-red-500'
                        : 'bg-white border border-gray-200 hover:bg-gray-50 hover:border-gray-300'
                    }`}
                  >
                    <div className="flex-shrink-0 mt-0.5">
                      <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center ${
                        selectedBuiltin === dt.name
                          ? 'bg-red-600 border-red-600'
                          : 'border-gray-300'
                      }`}>
                        {selectedBuiltin === dt.name && (
                          <Check size={10} className="text-white" strokeWidth={3} />
                        )}
                      </div>
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="font-semibold text-sm text-red-700">{dt.name}</div>
                      <div className="text-xs text-gray-600 mt-0.5">{dt.description}</div>
                    </div>
                  </button>
                ))}
              </div>

              {/* Info Box */}
              <div className="p-3 bg-blue-50 border-t border-blue-200 text-xs text-gray-700 flex-shrink-0">
                <p className="font-semibold mb-1">💡 Tip:</p>
                <p>Select a built-in datatype to use as-is, or switch to "Data range expression" tab to create restrictions like <code className="bg-white px-1 rounded">xsd:integer[&gt;= 0, &lt;= 100]</code></p>
              </div>
            </div>
          )}

          {activeTab === 'expression' && (
            <div className="flex flex-col h-full p-4">
              <div className="flex-1 flex flex-col">
                <label className="text-sm font-medium text-gray-700 mb-2">
                  Data Range Expression (Manchester Syntax)
                </label>
                <textarea
                  value={expression}
                  onChange={(e) => setExpression(e.target.value)}
                  placeholder="Enter data range expression..."
                  className="flex-1 px-3 py-2 border border-gray-300 rounded-md focus:ring-red-500 focus:border-red-500 text-sm font-mono resize-none"
                  autoFocus
                />
              </div>

              {/* Examples */}
              <div className="mt-4 p-3 bg-blue-50 border border-blue-200 rounded-md text-xs space-y-2">
                <p className="font-semibold text-gray-800">Examples:</p>
                <div className="space-y-1.5">
                  <div>
                    <div className="font-medium text-gray-700">Restriction:</div>
                    <code className="block bg-white px-2 py-1 rounded mt-0.5 text-[11px]">
                      xsd:integer[&gt;= 0, &lt;= 100]
                    </code>
                    <div className="text-gray-600 mt-0.5">Integers between 0 and 100</div>
                  </div>
                  <div>
                    <div className="font-medium text-gray-700">Enumeration:</div>
                    <code className="block bg-white px-2 py-1 rounded mt-0.5 text-[11px]">
                      &#123; "low" , "medium" , "high" &#125;
                    </code>
                    <div className="text-gray-600 mt-0.5">One of three specific values</div>
                  </div>
                  <div>
                    <div className="font-medium text-gray-700">Union:</div>
                    <code className="block bg-white px-2 py-1 rounded mt-0.5 text-[11px]">
                      xsd:string or xsd:integer
                    </code>
                    <div className="text-gray-600 mt-0.5">Either a string or integer</div>
                  </div>
                  <div>
                    <div className="font-medium text-gray-700">Intersection:</div>
                    <code className="block bg-white px-2 py-1 rounded mt-0.5 text-[11px]">
                      xsd:integer[&gt;= 0] and xsd:integer[&lt;= 100]
                    </code>
                    <div className="text-gray-600 mt-0.5">Non-negative integers up to 100</div>
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-gray-200 flex justify-between items-center flex-shrink-0">
          <div className="text-xs text-gray-500">
            {activeTab === 'builtin' && selectedBuiltin && (
              <span>Selected: <strong className="text-red-700">{selectedBuiltin}</strong></span>
            )}
          </div>
          <div className="flex gap-3">
            <button
              onClick={handleClose}
              className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
            >
              Cancel
            </button>
            <button
              onClick={handleConfirm}
              disabled={!canConfirm}
              className={`px-4 py-2 text-sm rounded-md ${
                canConfirm
                  ? 'bg-red-600 text-white hover:bg-red-700'
                  : 'bg-gray-300 text-gray-500 cursor-not-allowed'
              }`}
            >
              OK
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default DatatypeDefinitionDialog;
