import React from 'react';
import { Users, TrendingUp } from 'lucide-react';

interface AuthorActivity {
  author: string;
  additions: number;
  deletions: number;
  modifications: number;
  total: number;
}

interface AuthorActivityChartProps {
  data: AuthorActivity[];
}

const AuthorActivityChart: React.FC<AuthorActivityChartProps> = ({ data }) => {
  const maxTotal = Math.max(...data.map(d => d.total), 1);

  return (
    <div className="bg-white rounded-lg border p-4">
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-semibold text-gray-700 flex items-center gap-2">
          <Users className="w-4 h-4" />
          Author Contributions
        </h3>
        <TrendingUp className="w-4 h-4 text-gray-400" />
      </div>

      <div className="space-y-3">
        {data.map((author, idx) => {
          const percentage = (author.total / maxTotal) * 100;

          return (
            <div key={idx} className="space-y-1">
              <div className="flex items-center justify-between text-sm">
                <span className="font-medium text-gray-700">{author.author}</span>
                <span className="text-gray-500">{author.total} changes</span>
              </div>

              {}
              <div className="h-6 bg-gray-100 rounded-full overflow-hidden flex">
                <div
                  className="bg-green-500 flex items-center justify-center text-xs text-white font-medium"
                  style={{ width: `${(author.additions / author.total) * percentage}%` }}
                  title={`${author.additions} additions`}
                >
                  {author.additions > 0 && author.additions}
                </div>
                <div
                  className="bg-red-500 flex items-center justify-center text-xs text-white font-medium"
                  style={{ width: `${(author.deletions / author.total) * percentage}%` }}
                  title={`${author.deletions} deletions`}
                >
                  {author.deletions > 0 && author.deletions}
                </div>
                <div
                  className="bg-blue-500 flex items-center justify-center text-xs text-white font-medium"
                  style={{ width: `${(author.modifications / author.total) * percentage}%` }}
                  title={`${author.modifications} modifications`}
                >
                  {author.modifications > 0 && author.modifications}
                </div>
              </div>

              {}
              <div className="flex gap-3 text-xs text-gray-600">
                <span className="flex items-center gap-1">
                  <span className="w-2 h-2 rounded-full bg-green-500"></span>
                  {author.additions} added
                </span>
                <span className="flex items-center gap-1">
                  <span className="w-2 h-2 rounded-full bg-red-500"></span>
                  {author.deletions} deleted
                </span>
                <span className="flex items-center gap-1">
                  <span className="w-2 h-2 rounded-full bg-blue-500"></span>
                  {author.modifications} modified
                </span>
              </div>
            </div>
          );
        })}
      </div>

      {data.length === 0 && (
        <div className="text-center py-8 text-gray-500">
          <Users className="w-8 h-8 mx-auto mb-2 opacity-50" />
          <p className="text-sm">No author activity yet</p>
        </div>
      )}
    </div>
  );
};

export default AuthorActivityChart;
