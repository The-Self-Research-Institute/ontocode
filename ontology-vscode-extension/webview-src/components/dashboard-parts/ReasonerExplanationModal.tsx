import React from "react";
import { AlertCircle, Loader2 } from "lucide-react";

export const ReasonerExplanationModal = ({
  isOpen,
  onClose,
  data,
  loading,
  error,
}: {
  isOpen: boolean;
  onClose: () => void;
  data: any;
  loading: boolean;
  error: string | null;
}) => {
  if (!isOpen) return null;

  const causes = data?.causes || [];
  const isConsistent = data?.isConsistent ?? data?.consistent;
  const heading =
    isConsistent === false
      ? "Ontology is inconsistent"
      : isConsistent === true
        ? "Ontology is consistent"
        : "Explanation";

  return (
    <div className="fixed inset-0 z-[70] bg-black/50 flex items-center justify-center">
      <div
        className="bg-white rounded-xl shadow-2xl max-w-3xl w-full mx-4 overflow-hidden border"
        style={{ borderColor: "var(--color-border)" }}
      >
        <div className="flex items-center justify-between px-5 py-3 border-b bg-gray-50">
          <div className="flex items-center gap-2 text-sm font-semibold text-gray-800">
            <AlertCircle size={16} className="text-red-500" />
            Inconsistency explanation
          </div>
          <button onClick={onClose} className="text-xs text-gray-500 hover:text-gray-800">
            Close
          </button>
        </div>

        <div className="p-5 max-h-[70vh] overflow-y-auto text-sm text-gray-700">
          {loading ? (
            <div className="flex items-center gap-2 text-gray-600">
              <Loader2 size={18} className="animate-spin" />
              Computing explanation…
            </div>
          ) : error ? (
            <div className="flex items-center gap-2 text-red-600">
              <AlertCircle size={16} />
              {error}
            </div>
          ) : data ? (
            <>
              <div className="mb-4">
                <div className="text-xs uppercase text-gray-500 font-semibold mb-1">Summary</div>
                <div className="text-gray-800 font-medium">{data.message || heading}</div>
                {typeof data.totalIssues === "number" && (
                  <div className="text-[11px] text-gray-500">Issues detected: {data.totalIssues}</div>
                )}
                {isConsistent === false && (
                  <div className="mt-1 text-[11px] text-red-600">
                    The ontology failed consistency checks. See causes below.
                  </div>
                )}
                {isConsistent === true && (
                  <div className="mt-1 text-[11px] text-green-600">
                    The ontology is consistent; no inconsistency causes detected.
                  </div>
                )}
              </div>

              {causes.length === 0 ? (
                <div className="text-gray-600">No detailed causes returned by the backend.</div>
              ) : (
                <div className="space-y-3">
                  {causes.map((cause: any, idx: number) => (
                    <div key={idx} className="border rounded-lg p-3 bg-gray-50">
                      <div className="flex items-center justify-between mb-1">
                        <div className="text-sm font-semibold text-gray-800">{cause.title || cause.type}</div>
                        {cause.severity && (
                          <span className="text-[11px] uppercase text-red-600 font-semibold">{cause.severity}</span>
                        )}
                      </div>
                      {cause.description && <div className="text-xs text-gray-600 mb-2">{cause.description}</div>}
                      {cause.classes && Array.isArray(cause.classes) && (
                        <div className="text-[11px] text-gray-700 space-y-1">
                          {cause.classes.map((cls: any, i: number) => (
                            <div key={i} className="bg-white border rounded px-2 py-1">
                              <div className="font-semibold">{cls.label || cls.iri || "Class"}</div>
                              {cls.reason && <div className="text-gray-600">{cls.reason}</div>}
                              {cls.iri && <div className="text-gray-500">{cls.iri}</div>}
                            </div>
                          ))}
                        </div>
                      )}
                      {cause.violations && Array.isArray(cause.violations) && (
                        <div className="text-[11px] text-gray-700 space-y-1">
                          {cause.violations.map((violation: any, i: number) => {
                            const isPropertyViolation = violation.property || violation.propertyIri;
                            return (
                              <div key={i} className="bg-white border rounded px-2 py-1">
                                {violation.individual && <div className="font-semibold">{violation.individual}</div>}
                                {violation.disjointClasses && (
                                  <div className="text-gray-600">
                                    Classes: {(violation.disjointClasses as string[]).join(", ")}
                                  </div>
                                )}
                                {violation.individualIri && (
                                  <div className="text-gray-500">{violation.individualIri}</div>
                                )}
                                {isPropertyViolation && (
                                  <div className="space-y-1">
                                    <div className="font-semibold text-gray-800">
                                      {violation.property || "Property"}
                                    </div>
                                    {violation.propertyIri && (
                                      <div className="text-gray-500">{violation.propertyIri}</div>
                                    )}
                                    <div className="text-gray-600">
                                      Domain constraints: {violation.hasDomainConstraints ? "present" : "none"}; Range
                                      constraints: {violation.hasRangeConstraints ? "present" : "none"}
                                    </div>
                                  </div>
                                )}
                                {!violation.individual && !isPropertyViolation && (
                                  <pre className="text-[10px] text-gray-600 overflow-x-auto">
                                    {JSON.stringify(violation, null, 2)}
                                  </pre>
                                )}
                              </div>
                            );
                          })}
                        </div>
                      )}
                      {cause.tips && Array.isArray(cause.tips) && (
                        <ul className="list-disc list-inside text-[11px] text-gray-700 space-y-1">
                          {cause.tips.map((tip: string, i: number) => (
                            <li key={i}>{tip}</li>
                          ))}
                        </ul>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </>
          ) : (
            <div className="text-gray-600">No explanation available.</div>
          )}
        </div>
      </div>
    </div>
  );
};
