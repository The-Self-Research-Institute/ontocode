import React from "react";

export type MenuItem = { label: string; onClick?: () => void; disabled?: boolean };
export type Menu = { label: string; items: MenuItem[] };

export default function MenuBar({ menus }: { menus: Menu[] }) {
  return (
    <div className="w-full bg-[#6D4AFF] text-white select-none">
      <div className="mx-auto max-w-screen-2xl px-4 flex gap-6 h-9 items-center text-sm">
        {menus.map((m) => (
          <div key={m.label} className="relative group">
            <button className="font-medium">{m.label}</button>
            <div className="absolute hidden group-hover:block mt-2 bg-white text-gray-900 rounded-lg shadow-lg min-w-[180px] p-1 z-50">
              {m.items.map((it) => (
                <button
                  key={it.label}
                  onClick={it.onClick}
                  disabled={it.disabled}
                  className={`w-full text-left px-3 py-2 rounded-md hover:bg-gray-100 ${
                    it.disabled ? "opacity-50 cursor-not-allowed" : ""
                  }`}
                >
                  {it.label}
                </button>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
