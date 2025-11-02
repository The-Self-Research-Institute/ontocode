import React, { useState } from "react";
import { ChevronRight, ChevronDown, Plus, Trash2 } from "lucide-react";

export const AnnotationValue = ({ value }: { value: string }) => {
  let cleanedValue = value.toString();
  if (cleanedValue.startsWith('"')) cleanedValue = cleanedValue.substring(1);
  if (cleanedValue.endsWith('"^^xsd:string') || cleanedValue.endsWith('"')) {
    cleanedValue = cleanedValue.replace(/"\^\^xsd:string$/, "").replace(/"$/, "");
  }
  return <p className="text-sm text-gray-700 leading-relaxed whitespace-pre-wrap">{cleanedValue}</p>;
};

export const AnnotationsDisplay = ({ annotations, onDelete }: { annotations?: Record<string, string>, onDelete: (key: string) => void }) => {
  if (!annotations || Object.keys(annotations).length === 0) {
    return (
        <div className="p-2 text-xs text-gray-400 italic">No annotations</div>
    );
  }
  return (
    <div className="space-y-1 p-1">
      {Object.entries(annotations).map(([key, value]) => (
         <div key={key} className="group flex items-start p-1.5 rounded hover:bg-slate-100">
            <div className="w-1/3 text-xs font-medium text-gray-600 pr-2 break-words">{key}</div>
            <div className="w-2/3 text-xs text-gray-800 break-words flex justify-between items-start">
              <AnnotationValue value={value} />
              <button onClick={() => onDelete(key)} className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-red-200 ml-2">
                <Trash2 size={12} className="text-red-600" />
              </button>
            </div>
        </div>
      ))}
    </div>
  );
};


export const Panel = ({ title, children, actions, defaultOpen = true, themeColor }: { title: string, children?: React.ReactNode, actions?: React.ReactNode, defaultOpen?: boolean, themeColor?: string }) => {
    const [isOpen, setIsOpen] = useState(defaultOpen);
    const themeClasses = themeColor || 'bg-gradient-to-b from-[#F5F0E6] to-[#E1C688] text-black border-[#D6C9AD]';
    return (
        <div className={`border bg-white rounded-sm flex flex-col ${themeColor?.split(' ')[2] || 'border-[#D6C9AD]'}`}>
            <div className={`text-xs font-semibold p-1.5 flex items-center justify-between border-b ${themeClasses}`}>
                <div className="flex items-center">
                    <button onClick={() => setIsOpen(!isOpen)} className="mr-1 p-0.5 rounded hover:bg-black/10">
                        {isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                    </button>
                    <span>{title}</span>
                </div>
                <div className="flex items-center gap-1">{actions}</div>
            </div>
            <div className={`transition-all duration-300 ease-in-out overflow-hidden ${isOpen ? 'max-h-[1000px]' : 'max-h-0'}`}>
                {isOpen && <div className="bg-white overflow-y-auto">{children}</div>}
            </div>
        </div>
    );
};
