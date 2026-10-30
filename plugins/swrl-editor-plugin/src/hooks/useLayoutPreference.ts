import { useCallback, useState } from 'react';
import { readFlag, writeFlag } from '../storage';

export const CLASSIC_LAYOUT_KEY = 'ontocode.swrlClassicLayout';

export function useLayoutPreference(): [boolean, (classic: boolean) => void] {
  const [classic, setClassicState] = useState<boolean>(() => readFlag(CLASSIC_LAYOUT_KEY) === true);
  const setClassic = useCallback((value: boolean) => {
    writeFlag(CLASSIC_LAYOUT_KEY, value);
    setClassicState(value);
  }, []);
  return [classic, setClassic];
}
