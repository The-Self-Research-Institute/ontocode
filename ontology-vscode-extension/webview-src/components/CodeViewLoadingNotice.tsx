import React, { useEffect, useState } from "react";

const HINT_AFTER_SECONDS = 8;

export default function CodeViewLoadingNotice() {
  const [seconds, setSeconds] = useState(0);

  useEffect(() => {
    const timer = setInterval(() => setSeconds((value) => value + 1), 1000);
    return () => clearInterval(timer);
  }, []);

  return (
    <div role="status" className="flex flex-col items-center justify-center h-64 gap-2 text-gray-500">
      <div>Loading ontology content...</div>
      {seconds >= HINT_AFTER_SECONDS && (
        <div className="text-xs text-center max-w-md">
          Preparing the code view ({seconds} s so far). A large ontology can take a few minutes the first time.
          Opening it again is quick.
        </div>
      )}
    </div>
  );
}
