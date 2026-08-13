import React from 'react';

interface ChangeGraphProps {
  data: {
    labels: string[];
    additions: number[];
    deletions: number[];
    modifications: number[];
  };
}

const ChangeGraph: React.FC<ChangeGraphProps> = ({ data }) => {

  const maxValue = Math.max(
    ...data.additions,
    ...data.deletions,
    ...data.modifications,
    1 // Minimum of 1 to avoid division by zero
  );

  const totalChanges = data.additions.reduce((a, b) => a + b, 0) +
    data.deletions.reduce((a, b) => a + b, 0) +
    data.modifications.reduce((a, b) => a + b, 0);

  const barHeight = 120; // Max height in pixels

  return (
    <div className="bg-white rounded-lg border p-4">
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-semibold text-gray-700">Change Activity</h3>
        <div className="flex items-center gap-4 text-xs">
          <div className="flex items-center gap-1">
            <div className="w-3 h-3 rounded bg-green-500"></div>
            <span className="text-gray-600">Additions</span>
          </div>
          <div className="flex items-center gap-1">
            <div className="w-3 h-3 rounded bg-red-500"></div>
            <span className="text-gray-600">Deletions</span>
          </div>
          <div className="flex items-center gap-1">
            <div className="w-3 h-3 rounded bg-blue-500"></div>
            <span className="text-gray-600">Modifications</span>
          </div>
        </div>
      </div>

      {totalChanges === 0 ? (
        <div className="flex items-center justify-center h-40 text-gray-400 text-sm">
          No change activity to display
        </div>
      ) : (
        <div className="relative">
          {}
          <div className="absolute left-0 top-0 bottom-6 w-8 flex flex-col justify-between text-xs text-gray-400">
            <span>{maxValue}</span>
            <span>{Math.round(maxValue / 2)}</span>
            <span>0</span>
          </div>

          {}
          <div className="ml-10">
            {}
            <div className="absolute left-10 right-0 top-0 h-px bg-gray-100"></div>
            <div className="absolute left-10 right-0 top-1/2 h-px bg-gray-100" style={{ top: `${barHeight / 2}px` }}></div>
            <div className="absolute left-10 right-0 h-px bg-gray-200" style={{ top: `${barHeight}px` }}></div>

            {}
            <div className="flex items-end justify-around gap-1" style={{ height: `${barHeight}px` }}>
              {data.labels.map((label, i) => {
                const addHeight = (data.additions[i] / maxValue) * barHeight;
                const delHeight = (data.deletions[i] / maxValue) * barHeight;
                const modHeight = (data.modifications[i] / maxValue) * barHeight;

                return (
                  <div key={label} className="flex-1 flex flex-col items-center">
                    <div className="flex items-end gap-0.5 h-full">
                      {}
                      <div
                        className="w-3 bg-green-500 rounded-t transition-all duration-300 hover:bg-green-600 cursor-pointer"
                        style={{ height: `${Math.max(addHeight, data.additions[i] > 0 ? 4 : 0)}px` }}
                        title={`Additions: ${data.additions[i]}`}
                      />
                      {}
                      <div
                        className="w-3 bg-red-500 rounded-t transition-all duration-300 hover:bg-red-600 cursor-pointer"
                        style={{ height: `${Math.max(delHeight, data.deletions[i] > 0 ? 4 : 0)}px` }}
                        title={`Deletions: ${data.deletions[i]}`}
                      />
                      {}
                      <div
                        className="w-3 bg-blue-500 rounded-t transition-all duration-300 hover:bg-blue-600 cursor-pointer"
                        style={{ height: `${Math.max(modHeight, data.modifications[i] > 0 ? 4 : 0)}px` }}
                        title={`Modifications: ${data.modifications[i]}`}
                      />
                    </div>
                  </div>
                );
              })}
            </div>

            {}
            <div className="flex justify-around mt-2 text-xs text-gray-500">
              {data.labels.map(label => (
                <span key={label} className="flex-1 text-center">{label}</span>
              ))}
            </div>
          </div>
        </div>
      )}

      {}
      <div className="flex justify-center gap-6 mt-4 pt-3 border-t text-xs">
        <div className="text-center">
          <div className="font-semibold text-green-600">{data.additions.reduce((a, b) => a + b, 0)}</div>
          <div className="text-gray-500">Total Added</div>
        </div>
        <div className="text-center">
          <div className="font-semibold text-red-600">{data.deletions.reduce((a, b) => a + b, 0)}</div>
          <div className="text-gray-500">Total Deleted</div>
        </div>
        <div className="text-center">
          <div className="font-semibold text-blue-600">{data.modifications.reduce((a, b) => a + b, 0)}</div>
          <div className="text-gray-500">Total Modified</div>
        </div>
      </div>
    </div>
  );
};

export default ChangeGraph;
