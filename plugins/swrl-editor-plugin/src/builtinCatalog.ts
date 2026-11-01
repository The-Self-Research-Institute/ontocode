import type { BuiltInCategory, BuiltInInfo } from './types';

type Status = 'ok' | 'caution' | 'unavailable';

const fn = (prefix: string, name: string, description: string, args: string, example: string, status: Status = 'ok', note?: string): BuiltInInfo => ({
  name,
  fullName: `${prefix}:${name}`,
  description,
  signature: `${prefix}:${name}(${args})`,
  example,
  status,
  note,
});

const UNCHECKED = 'Not checked in this release.';
const NOT_IMPLEMENTED = 'The rule engine lists this function but does not implement it, so a rule that uses it cannot run.';

const b = (name: string, description: string, args: string, example: string, status: Status = 'ok', note?: string) =>
  fn('swrlb', name, description, args, example, status, note);

const COMPARE: BuiltInInfo[] = [
  b('equal', 'True when the two values are equal. Numbers, text, dates, times and true/false values can be compared with their own kind.', '?x, ?y', 'swrlb:equal(?age, 18)', 'caution', 'A whole number is never equal to text: swrlb:equal(?age, "18") does not match.'),
  b('notEqual', 'True when the two values are different.', '?x, ?y', 'swrlb:notEqual(?s1, ?s2)'),
  b('lessThan', 'True when the first value is smaller than the second.', '?x, ?y', 'swrlb:lessThan(?age, 18)'),
  b('lessThanOrEqual', 'True when the first value is smaller than or equal to the second.', '?x, ?y', 'swrlb:lessThanOrEqual(?score, 100)'),
  b('greaterThan', 'True when the first value is bigger than the second.', '?x, ?y', 'swrlb:greaterThan(?age, 18)'),
  b('greaterThanOrEqual', 'True when the first value is bigger than or equal to the second.', '?x, ?y', 'swrlb:greaterThanOrEqual(?score, 50)'),
  b('booleanNot', 'Flips a true/false value. The first value is the result.', '?result, ?flag', 'swrlb:booleanNot(?inactive, ?active)'),
];

const MATH: BuiltInInfo[] = [
  b('add', 'Adds the values after the first and puts the total in the first one.', '?result, ?x, ?y', 'swrlb:add(?total, ?a, ?b)'),
  b('subtract', 'Subtracts the third value from the second and puts the result in the first one.', '?result, ?x, ?y', 'swrlb:subtract(?diff, ?x, ?y)'),
  b('multiply', 'Multiplies the values after the first and puts the product in the first one.', '?result, ?x, ?y', 'swrlb:multiply(?product, ?a, ?b)'),
  b('divide', 'Divides the second value by the third.', '?result, ?x, ?y', 'swrlb:divide(?ratio, ?x, ?y)', 'ok', 'The answer is exact: 7 divided by 2 gives 3.5. A repeating answer such as 1 divided by 3 is cut after 20 decimal places.'),
  b('integerDivide', 'Whole-number division: how many times the third value fits into the second.', '?result, ?x, ?y', 'swrlb:integerDivide(?q, ?x, 2)', 'ok', 'The answer is cut towards zero: 7 divided by 2 gives 3, and -7 divided by 2 gives -3.'),
  b('mod', 'Remainder after dividing the second value by the third.', '?result, ?x, ?y', 'swrlb:mod(?rem, ?x, 2)'),
  b('pow', 'Raises the second value to the power of the third.', '?result, ?x, ?y', 'swrlb:pow(?square, ?x, 2)'),
  b('unaryPlus', 'Copies a number unchanged.', '?result, ?x', 'swrlb:unaryPlus(?same, ?x)'),
  b('unaryMinus', 'Changes the sign of a number.', '?result, ?x', 'swrlb:unaryMinus(?neg, ?x)'),
  b('abs', 'Absolute value (drops the minus sign).', '?result, ?x', 'swrlb:abs(?pos, ?val)'),
  b('ceiling', 'Rounds up to the next whole number.', '?result, ?x', 'swrlb:ceiling(?up, ?val)'),
  b('floor', 'Rounds down to the previous whole number.', '?result, ?x', 'swrlb:floor(?down, ?val)'),
  b('round', 'Rounds to the nearest whole number.', '?result, ?x', 'swrlb:round(?rounded, ?val)', 'ok', 'A value exactly halfway goes up: 2.5 gives 3 and -2.5 gives -2. Use swrlb:roundHalfToEven to send halfway values to the even neighbour.'),
  b('roundHalfToEven', 'Rounds to the nearest whole number; a value exactly halfway goes to the even neighbour (2.5 gives 2).', '?result, ?x', 'swrlb:roundHalfToEven(?rounded, ?val)', 'caution', 'Takes only two values here (no precision argument).'),
  b('sin', 'Sine of an angle in radians.', '?result, ?x', 'swrlb:sin(?s, ?angle)'),
  b('cos', 'Cosine of an angle in radians.', '?result, ?x', 'swrlb:cos(?c, ?angle)'),
  b('tan', 'Tangent of an angle in radians.', '?result, ?x', 'swrlb:tan(?t, ?angle)'),
];

const TEXT: BuiltInInfo[] = [
  b('stringEqualIgnoreCase', 'True when two texts are equal, ignoring upper and lower case.', '?s1, ?s2', 'swrlb:stringEqualIgnoreCase(?name, "ADA")'),
  b('stringConcat', 'Joins texts (or numbers) together; the first value is the result.', '?result, ?s1, ?s2', 'swrlb:stringConcat(?full, ?first, " ", ?last)'),
  b('stringLength', 'Number of characters in a text.', '?result, ?s', 'swrlb:stringLength(?len, ?name)'),
  b('substring', 'Cuts a piece out of a text. Counting starts at 1; leave out the last value to take everything to the end.', '?result, ?s, ?start, ?length', 'swrlb:substring(?part, ?s, 2, 3)', 'ok', 'Never stops the rule for a position outside the text: "Alpha" with 2 and 3 gives "lph", and with 4 and 50 gives "ha".'),
  b('substringBefore', 'The part of a text before the first match of the second text. Gives an empty text when there is no match.', '?result, ?s, ?find', 'swrlb:substringBefore(?first, ?name, " ")'),
  b('substringAfter', 'The part of a text after the first match of the second text.', '?result, ?s, ?find', 'swrlb:substringAfter(?last, ?name, " ")'),
  b('normalizeSpace', 'Trims the ends and turns every run of spaces into one space.', '?result, ?s', 'swrlb:normalizeSpace(?tidy, ?name)'),
  b('upperCase', 'Converts a text to capital letters.', '?result, ?s', 'swrlb:upperCase(?upper, ?name)'),
  b('lowerCase', 'Converts a text to small letters.', '?result, ?s', 'swrlb:lowerCase(?lower, ?name)'),
  b('translate', 'Replaces each character of the second text with the character in the same place in the third text. A character with no partner in the third text is removed.', '?result, ?s, ?from, ?to', 'swrlb:translate(?out, ?s, "-", "_")'),
  b('contains', 'True when the text contains the other text.', '?s, ?part', 'swrlb:contains(?name, "John")'),
  b('containsIgnoreCase', 'True when the text contains the other text, ignoring upper and lower case.', '?s, ?part', 'swrlb:containsIgnoreCase(?name, "john")'),
  b('startsWith', 'True when the text begins with the other text.', '?s, ?prefix', 'swrlb:startsWith(?name, "Dr.")'),
  b('endsWith', 'True when the text ends with the other text.', '?s, ?suffix', 'swrlb:endsWith(?email, ".edu")'),
  b('matches', 'True when the text contains a match for a regular expression (a pattern such as [A-Z]{3}-[0-9]+). Start the pattern with ^ and end it with $ to match the whole text. An optional third value holds options: i ignores capital letters, s lets . match a line break, m treats each line separately, x allows spaces in the pattern, q reads the pattern as plain text.', '?s, ?pattern, ?options', 'swrlb:matches(?code, "^[A-Z]{3}-[0-9]+$")'),
  b('replace', 'Replaces every match of a pattern in a text.', '?result, ?s, ?pattern, ?replacement', 'swrlb:replace(?clean, ?s, " ", "_")'),
  b('tokenize', 'Splits a text at a separator. Each piece becomes its own result.', '?result, ?s, ?separator', 'swrlb:tokenize(?word, ?sentence, " ")'),
];

const DATE_TIME: BuiltInInfo[] = [
  b('date', 'Builds a date from year, month, day and time zone. The time zone is "" for none, Z, or a shift such as +05:30.', '?result, ?y, ?m, ?d, ?tz', 'swrlb:date(?d, 2024, 1, 15, "")', 'ok', 'Stops the rule for a day that does not exist, such as 30 February.'),
  b('time', 'Builds a time of day. The time zone is "" for none, Z, or a shift such as +05:30.', '?result, ?h, ?m, ?s, ?tz', 'swrlb:time(?t, 14, 30, 0, "")', 'ok', 'Gives the form 14:30:00. Stops the rule for an hour, minute or second out of range.'),
  b('dateTime', 'Builds a date and time. The time zone is "" for none, Z, or a shift such as +05:30.', '?result, ?y, ?m, ?d, ?h, ?min, ?s, ?tz', 'swrlb:dateTime(?dt, 2024, 1, 15, 14, 30, 0, "")', 'ok', 'Gives the form 2024-01-15T14:30:00. Stops the rule for a date or time that does not exist.'),
  b('yearMonthDuration', 'Builds a length of time in years and months.', '?result, ?years, ?months', 'swrlb:yearMonthDuration(?dur, 1, 6)'),
  b('dayTimeDuration', 'Builds a length of time in days, hours, minutes and seconds.', '?result, ?d, ?h, ?m, ?s', 'swrlb:dayTimeDuration(?dur, 2, 12, 0, 0)'),
  b('addYearMonthDurations', 'Adds two year/month durations.', '?result, ?d1, ?d2', 'swrlb:addYearMonthDurations(?sum, ?a, ?b)', 'ok', 'P1Y6M plus P8M gives P2Y2M. Both values must be durations of years and months only.'),
  b('addDayTimeDurations', 'Adds two day/time durations.', '?result, ?d1, ?d2', 'swrlb:addDayTimeDurations(?sum, ?a, ?b)'),
  b('subtractYearMonthDurations', 'Subtracts one year/month duration from another.', '?result, ?d1, ?d2', 'swrlb:subtractYearMonthDurations(?diff, ?a, ?b)', 'ok', 'P1Y6M minus P8M gives P10M. A negative answer is written with a leading minus, such as -P10M.'),
  b('subtractDayTimeDurations', 'Subtracts one day/time duration from another.', '?result, ?d1, ?d2', 'swrlb:subtractDayTimeDurations(?diff, ?a, ?b)', 'ok', 'P1D minus PT12H gives PT12H. A negative answer is written with a leading minus, such as -PT12H.'),
  b('multiplyYearMonthDuration', 'Multiplies a year/month duration by a number.', '?result, ?duration, ?n', 'swrlb:multiplyYearMonthDuration(?d2, ?d1, 2)', 'ok', 'The answer is rounded to a whole number of months.'),
  b('divideYearMonthDuration', 'Divides a year/month duration by a number.', '?result, ?duration, ?n', 'swrlb:divideYearMonthDuration(?d2, ?d1, 2)', 'ok', 'The answer is rounded to a whole number of months.'),
  b('multiplyDayTimeDuration', 'Multiplies a day/time duration by a number.', '?result, ?duration, ?n', 'swrlb:multiplyDayTimeDuration(?d2, ?d1, 2)'),
  b('divideDayTimeDuration', 'Divides a day/time duration by a number.', '?result, ?duration, ?n', 'swrlb:divideDayTimeDuration(?d2, ?d1, 2)', 'ok', 'Fractions of a second are kept to 6 places.'),
  b('subtractDates', 'The length of time between two dates, as a duration such as P14D.', '?result, ?date1, ?date2', 'swrlb:subtractDates(?gap, ?d1, ?d2)', 'ok', 'The answer is the first date minus the second, so it is negative when the first date is earlier.'),
  b('subtractTimes', 'The length of time between two times of day, as a duration such as PT2H30M.', '?result, ?time1, ?time2', 'swrlb:subtractTimes(?gap, ?t1, ?t2)', 'ok', 'The answer is the first time minus the second, so it is negative when the first time is earlier.'),
  b('subtractDateTimesYieldingYearMonthDuration', 'Whole years and months between two date-times, as a duration such as P1Y9M.', '?result, ?dt1, ?dt2', 'swrlb:subtractDateTimesYieldingYearMonthDuration(?gap, ?a, ?b)', 'ok', 'Only complete months count; the days left over are dropped.'),
  b('subtractDateTimesYieldingDayTimeDuration', 'Days and time between two date-times, as a duration such as P1DT12H.', '?result, ?dt1, ?dt2', 'swrlb:subtractDateTimesYieldingDayTimeDuration(?gap, ?a, ?b)'),
  b('addYearMonthDurationToDate', 'Moves a date forward by years and months.', '?result, ?date, ?duration', 'swrlb:addYearMonthDurationToDate(?later, ?d, ?dur)'),
  b('addYearMonthDurationToDateTime', 'Moves a date-time forward by years and months.', '?result, ?dateTime, ?duration', 'swrlb:addYearMonthDurationToDateTime(?later, ?dt, ?dur)', 'ok', 'The time of day stays the same. When the new month is shorter the day moves back to its last day: 31 January plus one month gives 29 February in 2024.'),
  b('addDayTimeDurationToDate', 'Moves a date forward by days.', '?result, ?date, ?duration', 'swrlb:addDayTimeDurationToDate(?later, ?d, ?dur)'),
  b('addDayTimeDurationToDateTime', 'Moves a date-time forward by days and time.', '?result, ?dateTime, ?duration', 'swrlb:addDayTimeDurationToDateTime(?later, ?dt, ?dur)'),
  b('addDayTimeDurationToTime', 'Moves a time of day forward.', '?result, ?time, ?duration', 'swrlb:addDayTimeDurationToTime(?later, ?t, ?dur)', 'ok', 'The time wraps around midnight: 23:15:00 plus 2 hours gives 01:15:00.'),
  b('subtractYearMonthDurationFromDate', 'Moves a date back by years and months.', '?result, ?date, ?duration', 'swrlb:subtractYearMonthDurationFromDate(?earlier, ?d, ?dur)'),
  b('subtractYearMonthDurationFromDateTime', 'Moves a date-time back by years and months.', '?result, ?dateTime, ?duration', 'swrlb:subtractYearMonthDurationFromDateTime(?earlier, ?dt, ?dur)', 'ok', 'The time of day stays the same. When the new month is shorter the day moves back to its last day.'),
  b('subtractDayTimeDurationFromDate', 'Moves a date back by days.', '?result, ?date, ?duration', 'swrlb:subtractDayTimeDurationFromDate(?earlier, ?d, ?dur)'),
  b('subtractDayTimeDurationFromDateTime', 'Moves a date-time back by days and time.', '?result, ?dateTime, ?duration', 'swrlb:subtractDayTimeDurationFromDateTime(?earlier, ?dt, ?dur)'),
  b('subtractDayTimeDurationFromTime', 'Moves a time of day back.', '?result, ?time, ?duration', 'swrlb:subtractDayTimeDurationFromTime(?earlier, ?t, ?dur)', 'ok', 'The time wraps around midnight: 01:15:00 minus 2 hours gives 23:15:00.'),
];

const WEB_AND_LISTS: BuiltInInfo[] = [
  b('anyURI', 'Builds a web address from its parts. A port can be given after the host.', '?result, ?scheme, ?host, ?path, ?query, ?fragment', 'swrlb:anyURI(?u, "http", "example.org", "/a", "", "")', 'ok', 'Empty parts are left out. With a port the values are scheme, host, port, path, query and fragment.'),
  b('resolveURI', 'Resolves a relative address against a base address.', '?result, ?relative, ?base', 'swrlb:resolveURI(?u, "b/c", "http://example.org/a/")', 'ok', 'The base must be a full web address.'),
  b('length', 'Number of items in a list.', '?result, ?list', 'swrlb:length(?n, ?list)', 'unavailable', NOT_IMPLEMENTED),
  b('first', 'First item of a list.', '?result, ?list', 'swrlb:first(?f, ?list)', 'unavailable', NOT_IMPLEMENTED),
  b('rest', 'A list without its first item.', '?result, ?list', 'swrlb:rest(?r, ?list)', 'unavailable', NOT_IMPLEMENTED),
  b('empty', 'True when a list has no items.', '?list', 'swrlb:empty(?list)', 'unavailable', NOT_IMPLEMENTED),
  b('member', 'True when a value is in a list.', '?value, ?list', 'swrlb:member("Beta", ?list)', 'unavailable', NOT_IMPLEMENTED),
  b('sublist', 'A slice of a list.', '?result, ?list, ?from, ?to', 'swrlb:sublist(?s, ?list, 0, 1)', 'unavailable', NOT_IMPLEMENTED),
  b('listConcat', 'Joins lists together.', '?result, ?list1, ?list2', 'swrlb:listConcat(?c, ?a, ?b)', 'unavailable', NOT_IMPLEMENTED),
  b('listIntersection', 'Items found in both lists.', '?result, ?list1, ?list2', 'swrlb:listIntersection(?c, ?a, ?b)', 'unavailable', NOT_IMPLEMENTED),
  b('listSubtraction', 'Items of the first list that are not in the second.', '?result, ?list1, ?list2', 'swrlb:listSubtraction(?c, ?a, ?b)', 'unavailable', NOT_IMPLEMENTED),
];

const m = (name: string, description: string, args: string, example: string, status: Status = 'ok', note?: string) =>
  fn('swrlm', name, description, args, example, status, note);

const EXTENSIONS: BuiltInInfo[] = [
  m('sqrt', 'Square root.', '?result, ?x', 'swrlm:sqrt(?root, ?val)'),
  m('log', 'Logarithm. With two values it is the base-10 logarithm; a third value gives the logarithm to that base.', '?result, ?x, ?base', 'swrlm:log(?l, ?val, 2)', 'ok', 'With two values this is base 10, not the natural logarithm.'),
  m('eval', 'Calculates a formula. The names in the formula must match the rule variables.', '?result, "formula", ?x', 'swrlm:eval(?r, "v*2+1", ?v)', 'ok', 'Use the variable name itself in the formula ("v*2+1" with ?v). Whole-number, decimal and double data all work; the answer is always a double (6.0).'),
];

const x = (name: string, description: string, args: string, example: string, status: Status = 'ok', note?: string) =>
  fn('swrlx', name, description, args, example, status, note);

const INDIVIDUALS: BuiltInInfo[] = [
  x('makeOWLThing', 'Creates a new individual for each value, so a rule can conclude something about a new thing.', '?newIndividual, ?value', 'swrlx:makeOWLThing(?x, ?r)', 'caution', 'The new individuals get random names and are not saved in the ontology.'),
  x('createOWLThing', 'Creates a new individual.', '?newIndividual, ?value', 'swrlx:createOWLThing(?x, ?r)', 'caution', 'The new individuals get random names and are not saved in the ontology.'),
  x('makeOWLIndividual', 'Creates a new individual of a class for each value.', '?newIndividual, ?value', 'swrlx:makeOWLIndividual(?x, ?r)', 'caution', UNCHECKED),
  x('makeOWLClass', 'Creates a new class for each value.', '?newClass, ?value', 'swrlx:makeOWLClass(?c, ?r)', 'caution', 'The new class gets a random name and is not saved in the ontology. It is a class, not an individual, so it cannot be used as the individual of a conclusion.'),
  x('invokeSWRLBuiltIn', 'Calls another built-in by its name.', '"builtin", index, ?args', 'swrlx:invokeSWRLBuiltIn("swrlb:greaterThan", 2, 1)', 'caution', 'Only checked with constant arguments.'),
];

const T_DESCRIPTIONS: Array<[string, string]> = [
  ['equals', 'The two time spans are the same.'],
  ['before', 'The first time span ends before the second begins.'],
  ['after', 'The first time span begins after the second ends.'],
  ['meets', 'The first time span ends exactly when the second begins.'],
  ['metBy', 'The first time span begins exactly when the second ends.'],
  ['overlaps', 'The first time span starts first and overlaps the start of the second.'],
  ['overlappedBy', 'The first time span starts later and overlaps the end of the second.'],
  ['contains', 'The first time span completely contains the second.'],
  ['during', 'The first time span lies completely inside the second.'],
  ['starts', 'Both begin together and the first ends first.'],
  ['startedBy', 'Both begin together and the first ends last.'],
  ['finishes', 'Both end together and the first begins later.'],
  ['finishedBy', 'Both end together and the first begins earlier.'],
  ['intersects', 'The two time spans share some time.'],
];

const TESTED_TEMPORAL = new Set(['before', 'after', 'equals', 'notBefore']);

const temporalRelations: BuiltInInfo[] = [];
for (const [name, description] of T_DESCRIPTIONS) {
  const negated = 'not' + name.charAt(0).toUpperCase() + name.slice(1);
  const checked = TESTED_TEMPORAL.has(name);
  temporalRelations.push(fn('temporal', name, description, '?start1, ?start2, "Days"', `temporal:${name}(?a, "2024-12-31T00:00:00", "Days")`, checked ? 'ok' : 'caution', checked ? undefined : UNCHECKED));
  temporalRelations.push(fn('temporal', negated, 'Opposite of temporal:' + name + '.', '?start1, ?start2, "Days"', `temporal:${negated}(?a, "2024-12-31T00:00:00", "Days")`, TESTED_TEMPORAL.has(negated) ? 'ok' : 'caution', TESTED_TEMPORAL.has(negated) ? undefined : UNCHECKED));
}

const temporalDuration: BuiltInInfo[] = [
  fn('temporal', 'add', 'Adds an amount of time to a date-time.', '?result, ?dateTime, ?amount, "Days"', 'temporal:add(?later, ?stamp, 1, "Days")'),
  fn('temporal', 'duration', 'Counts the units of time between two date-times.', '?result, ?start, ?end, "Days"', 'temporal:duration(?n, ?start, ?end, "Days")'),
  fn('temporal', 'durationEqualTo', 'The time between two date-times equals an amount.', '?start, ?end, ?amount, "Days"', 'temporal:durationEqualTo(?start, ?end, 3, "Days")', 'ok', 'Counts whole units between the two date-times, whichever comes first: 3.9 days counts as 3 Days. The unit is Years, Months, Days, Hours, Minutes, Seconds or Milliseconds.'),
  fn('temporal', 'durationGreaterThan', 'The time between two date-times is more than an amount.', '?start, ?end, ?amount, "Days"', 'temporal:durationGreaterThan(?start, ?end, 3, "Days")', 'ok', 'Counts whole units between the two date-times, whichever comes first: 3.9 days counts as 3 Days. The unit is Years, Months, Days, Hours, Minutes, Seconds or Milliseconds.'),
  fn('temporal', 'durationGreaterThanOrEqualTo', 'The time between two date-times is at least an amount.', '?start, ?end, ?amount, "Days"', 'temporal:durationGreaterThanOrEqualTo(?start, ?end, 3, "Days")', 'ok', 'Counts whole units between the two date-times, whichever comes first: 3.9 days counts as 3 Days. The unit is Years, Months, Days, Hours, Minutes, Seconds or Milliseconds.'),
  fn('temporal', 'durationLessThan', 'The time between two date-times is less than an amount.', '?start, ?end, ?amount, "Days"', 'temporal:durationLessThan(?start, ?end, 3, "Days")', 'ok', 'Counts whole units between the two date-times, whichever comes first: 3.9 days counts as 3 Days. The unit is Years, Months, Days, Hours, Minutes, Seconds or Milliseconds.'),
  fn('temporal', 'durationLessThanOrEqualTo', 'The time between two date-times is at most an amount.', '?start, ?end, ?amount, "Days"', 'temporal:durationLessThanOrEqualTo(?start, ?end, 3, "Days")', 'ok', 'Counts whole units between the two date-times, whichever comes first: 3.9 days counts as 3 Days. The unit is Years, Months, Days, Hours, Minutes, Seconds or Milliseconds.'),
  fn('temporal', 'notDurationEqualTo', 'Opposite of temporal:durationEqualTo.', '?start, ?end, ?amount, "Days"', 'temporal:notDurationEqualTo(?start, ?end, 3, "Days")', 'ok', 'Counts whole units between the two date-times, whichever comes first: 3.9 days counts as 3 Days. The unit is Years, Months, Days, Hours, Minutes, Seconds or Milliseconds.'),
  fn('temporal', 'notDurationGreaterThan', 'Opposite of temporal:durationGreaterThan.', '?start, ?end, ?amount, "Days"', 'temporal:notDurationGreaterThan(?start, ?end, 3, "Days")', 'ok', 'Counts whole units between the two date-times, whichever comes first: 3.9 days counts as 3 Days. The unit is Years, Months, Days, Hours, Minutes, Seconds or Milliseconds.'),
  fn('temporal', 'notDurationGreaterThanOrEqualTo', 'Opposite of temporal:durationGreaterThanOrEqualTo.', '?start, ?end, ?amount, "Days"', 'temporal:notDurationGreaterThanOrEqualTo(?start, ?end, 3, "Days")', 'ok', 'Counts whole units between the two date-times, whichever comes first: 3.9 days counts as 3 Days. The unit is Years, Months, Days, Hours, Minutes, Seconds or Milliseconds.'),
  fn('temporal', 'notDurationLessThan', 'Opposite of temporal:durationLessThan.', '?start, ?end, ?amount, "Days"', 'temporal:notDurationLessThan(?start, ?end, 3, "Days")', 'ok', 'Counts whole units between the two date-times, whichever comes first: 3.9 days counts as 3 Days. The unit is Years, Months, Days, Hours, Minutes, Seconds or Milliseconds.'),
  fn('temporal', 'notDurationLessThanOrEqualTo', 'Opposite of temporal:durationLessThanOrEqualTo.', '?start, ?end, ?amount, "Days"', 'temporal:notDurationLessThanOrEqualTo(?start, ?end, 3, "Days")', 'ok', 'Counts whole units between the two date-times, whichever comes first: 3.9 days counts as 3 Days. The unit is Years, Months, Days, Hours, Minutes, Seconds or Milliseconds.'),
];

const q = (name: string, description: string, args: string, example: string, status: Status = 'ok', note?: string) =>
  fn('sqwrl', name, description, args, example, status, note);

const SQWRL_RESULT: BuiltInInfo[] = [
  q('select', 'Chooses the columns of the result table.', '?x, ?y', 'sqwrl:select(?person, ?age)'),
  q('selectDistinct', 'Chooses the columns and drops repeated rows.', '?x', 'sqwrl:selectDistinct(?city)'),
  q('count', 'Counts the rows.', '?x', 'sqwrl:count(?person)'),
  q('countDistinct', 'Counts the different values.', '?x', 'sqwrl:countDistinct(?city)'),
  q('sum', 'Adds up the numbers in a column.', '?x', 'sqwrl:sum(?amount)', 'caution', 'Numbers from data properties work; the total is shown with a decimal point (19.0). A number calculated inside the same query by swrlb:add and similar is not accepted.'),
  q('avg', 'Average of the numbers in a column.', '?x', 'sqwrl:avg(?score)', 'caution', 'Numbers from data properties work. A number calculated inside the same query is not accepted.'),
  q('median', 'Middle value of the numbers in a column.', '?x', 'sqwrl:median(?score)', 'caution', 'Numbers from data properties work. A number calculated inside the same query is not accepted.'),
  q('min', 'Smallest value in a column.', '?x', 'sqwrl:min(?age)', 'ok', 'Works on numbers, text and dates. Text and dates are compared in sorted order.'),
  q('max', 'Biggest value in a column.', '?x', 'sqwrl:max(?salary)', 'ok', 'Works on numbers, text and dates. Text and dates are compared in sorted order.'),
  q('orderBy', 'Sorts the rows from small to big.', '?x', 'sqwrl:orderBy(?name)'),
  q('orderByDescending', 'Sorts the rows from big to small.', '?x', 'sqwrl:orderByDescending(?age)'),
  q('columnNames', 'Gives the columns your own headings.', '"Name", "Age"', 'sqwrl:columnNames("Person", "Age")'),
  q('limit', 'Keeps only the first rows.', 'n', 'sqwrl:limit(10)'),
  q('nth', 'Keeps only row number n (counting from 1) of the sorted result.', 'n', 'sqwrl:nth(2)', 'caution', 'Needs sqwrl:orderBy.'),
  q('nthSlice', 'Keeps a block of rows starting at row n.', 'n, size', 'sqwrl:nthSlice(2, 3)', 'caution', 'Needs sqwrl:orderBy.'),
  q('nthLastSlice', 'Keeps a block of rows counted from the end.', 'n, size', 'sqwrl:nthLastSlice(1, 2)', 'caution', 'Needs sqwrl:orderBy.'),
  q('firstN', 'Keeps the first n rows of the sorted result.', 'n', 'sqwrl:firstN(3)', 'caution', 'Needs sqwrl:orderBy.'),
  q('lastN', 'Keeps the last n rows of the sorted result.', 'n', 'sqwrl:lastN(3)', 'caution', 'Needs sqwrl:orderBy.'),
  q('greatestN', 'Keeps the n rows with the biggest values.', 'n', 'sqwrl:greatestN(3)', 'caution', 'Needs sqwrl:orderBy.'),
  q('leastN', 'Keeps the n rows with the smallest values.', 'n', 'sqwrl:leastN(3)', 'caution', 'Needs sqwrl:orderBy.'),
  q('notNth', 'Drops row number n.', 'n', 'sqwrl:notNth(1)', 'caution', 'Needs sqwrl:orderBy.'),
  q('notFirstN', 'Drops the first n rows.', 'n', 'sqwrl:notFirstN(1)', 'caution', 'Needs sqwrl:orderBy.'),
  q('notLastN', 'Drops the last n rows.', 'n', 'sqwrl:notLastN(1)', 'caution', 'Needs sqwrl:orderBy.'),
  q('notNthSlice', 'Drops a block of rows starting at row n.', 'n, size', 'sqwrl:notNthSlice(1, 1)', 'caution', UNCHECKED),
  q('notNthLastSlice', 'Drops a block of rows counted from the end.', 'n, size', 'sqwrl:notNthLastSlice(1, 1)', 'caution', UNCHECKED),
  q('nthGreatestSlice', 'Keeps a block of rows starting at the nth biggest value.', 'n, size', 'sqwrl:nthGreatestSlice(1, 2)', 'caution', UNCHECKED),
  q('notNthGreatestSlice', 'Drops a block of rows starting at the nth biggest value.', 'n, size', 'sqwrl:notNthGreatestSlice(1, 2)', 'caution', UNCHECKED),
  q('notNthGreatest', 'Drops the row with the nth biggest value.', 'n', 'sqwrl:notNthGreatest(2)', 'caution', UNCHECKED),
  q('notGreatestN', 'Drops the n rows with the biggest values.', 'n', 'sqwrl:notGreatestN(1)', 'caution', UNCHECKED),
  q('notLeastN', 'Drops the n rows with the smallest values.', 'n', 'sqwrl:notLeastN(1)', 'caution', UNCHECKED),
  q('nthLast', 'Row number n counted from the end.', 'n', 'sqwrl:nthLast(1)', 'caution', 'Needs sqwrl:orderBy.'),
  q('nthGreatest', 'The nth biggest value.', 'n', 'sqwrl:nthGreatest(2)', 'caution', 'Needs sqwrl:orderBy.'),
  q('notNthLast', 'Drops row number n counted from the end.', 'n', 'sqwrl:notNthLast(1)', 'caution', 'Needs sqwrl:orderBy. Only n = 1 (drop the last row) is available.'),
];

const SQWRL_COLLECTIONS: BuiltInInfo[] = [
  q('makeSet', 'Collects values into a set (no repeats). Goes before the arrow.', '?set, ?x', 'sqwrl:makeSet(?s, ?age)'),
  q('makeBag', 'Collects values into a bag (repeats kept). Goes before the arrow.', '?bag, ?x', 'sqwrl:makeBag(?b, ?age)', 'caution', 'Only text and numbers can be collected; true/false values stop the query.'),
  q('groupBy', 'Makes one collection per value of a column. Goes before the arrow.', '?collection, ?x', 'sqwrl:groupBy(?s, ?city)', 'caution', 'Group by text or numbers, not true/false values.'),
  q('size', 'Number of items in a collection.', '?n, ?collection', 'sqwrl:size(?n, ?s)'),
  q('isEmpty', 'True when a collection has no items.', '?collection', 'sqwrl:isEmpty(?s)'),
  q('notEmpty', 'True when a collection has items.', '?collection', 'sqwrl:notEmpty(?s)'),
  q('element', 'Each item of a collection, one row per item.', '?item, ?collection', 'sqwrl:element(?e, ?s)'),
  q('notElement', 'True when a value is not in a collection.', '?value, ?collection', 'sqwrl:notElement(?v, ?s)', 'caution', UNCHECKED),
  q('contains', 'True when the first collection holds every item of the second collection.', '?collection1, ?collection2', 'sqwrl:contains(?a, ?b)', 'ok', 'Both values are collections made with sqwrl:makeSet or sqwrl:makeBag. To test a single value use sqwrl:element.'),
  q('notContains', 'True when the first collection does not hold every item of the second collection.', '?collection1, ?collection2', 'sqwrl:notContains(?a, ?b)', 'ok', 'Both values are collections made with sqwrl:makeSet or sqwrl:makeBag. To test a single value use sqwrl:notElement.'),
  q('first', 'First item of a collection.', '?item, ?collection', 'sqwrl:first(?e, ?s)'),
  q('last', 'Last item of a collection.', '?item, ?collection', 'sqwrl:last(?e, ?s)'),
  q('notFirst', 'True when a value is not the first item of a collection.', '?item, ?collection', 'sqwrl:notFirst(?e, ?s)', 'caution', UNCHECKED),
  q('notLast', 'True when a value is not the last item of a collection.', '?item, ?collection', 'sqwrl:notLast(?e, ?s)', 'caution', UNCHECKED),
  q('notGreatest', 'True when a value is not the biggest item of a collection.', '?item, ?collection', 'sqwrl:notGreatest(?e, ?s)', 'caution', UNCHECKED),
  q('notLeast', 'True when a value is not the smallest item of a collection.', '?item, ?collection', 'sqwrl:notLeast(?e, ?s)', 'caution', UNCHECKED),
  q('greatest', 'Biggest item of a collection.', '?item, ?collection', 'sqwrl:greatest(?e, ?s)'),
  q('least', 'Smallest item of a collection.', '?item, ?collection', 'sqwrl:least(?e, ?s)'),
  q('union', 'Items found in either collection.', '?result, ?a, ?b', 'sqwrl:union(?u, ?a, ?b)'),
  q('intersection', 'Items found in both collections.', '?result, ?a, ?b', 'sqwrl:intersection(?i, ?a, ?b)'),
  q('difference', 'Items of the first collection that are not in the second.', '?result, ?a, ?b', 'sqwrl:difference(?d, ?a, ?b)'),
  q('append', 'Joins two bags.', '?result, ?a, ?b', 'sqwrl:append(?all, ?a, ?b)'),
  q('equal', 'True when two collections have the same items.', '?a, ?b', 'sqwrl:equal(?a, ?b)'),
  q('notEqual', 'True when two collections differ.', '?a, ?b', 'sqwrl:notEqual(?a, ?b)'),
  q('intersects', 'True when two collections share an item.', '?a, ?b', 'sqwrl:intersects(?a, ?b)'),
  q('notIntersects', 'True when two collections share no item.', '?a, ?b', 'sqwrl:notIntersects(?a, ?b)'),
];

const ABOX: Array<[string, string, string]> = [
  ['caa', 'Class assertions: which individual belongs to which class.', '?class, ?individual'],
  ['dpaa', 'Data property assertions: individual, property and value.', '?individual, ?property, ?value'],
  ['opaa', 'Object property assertions: individual, property and the other individual.', '?individual, ?property, ?other'],
  ['ndpaa', 'Negative data property assertions.', '?individual, ?property, ?value'],
  ['nopaa', 'Negative object property assertions.', '?individual, ?property, ?other'],
  ['sia', 'Same-individual assertions.', '?individual1, ?individual2'],
  ['dia', 'Different-individual assertions.', '?individual1, ?individual2'],
];

const TBOX: Array<[string, string]> = [
  ['sca', 'Subclass axioms.'], ['eca', 'Equivalent class axioms.'], ['dca', 'Disjoint class axioms.'], ['dua', 'Disjoint union axioms.'],
  ['hka', 'Has-key axioms.'], ['cd', 'Class declarations.'], ['dd', 'Datatype declarations.'], ['dda', 'Datatype definition axioms.'],
  ['dpd', 'Data property domain axioms.'], ['dpra', 'Data property range axioms.'], ['dpda', 'Data property domain axioms (alternate form).'],
  ['opd', 'Object property domain axioms.'], ['opra', 'Object property range axioms.'], ['opda', 'Object property domain axioms (alternate form).'],
  ['apd', 'Annotation property domain axioms.'], ['fdpa', 'Functional data property axioms.'], ['fopa', 'Functional object property axioms.'],
  ['ifopa', 'Inverse functional object property axioms.'],
];

const RBOX: Array<[string, string]> = [
  ['sdpa', 'Sub data property axioms.'], ['sopa', 'Sub object property axioms.'], ['spa', 'Sub property axioms.'], ['spoca', 'Sub property chain axioms.'], ['dpda', 'Data property domain axioms.'],
  ['edpa', 'Equivalent data property axioms.'], ['eopa', 'Equivalent object property axioms.'], ['djdpa', 'Disjoint data property axioms.'],
  ['djopa', 'Disjoint object property axioms.'], ['iopa', 'Inverse object property axioms.'], ['iropa', 'Irreflexive object property axioms.'],
  ['ropa', 'Reflexive object property axioms.'], ['aopa', 'Asymmetric object property axioms.'], ['topa', 'Transitive object property axioms.'],
];

const AXIOM_NOTE = 'Reads the ontology axioms. ' + UNCHECKED;

const AXIOM_QUERIES: BuiltInInfo[] = [
  ...ABOX.map(([name, description, args]) => fn('abox', name, description, args, `abox:${name}(${args.split(', ').join(', ')})`, ['caa', 'dpaa', 'opaa'].includes(name) ? 'ok' : 'caution', ['caa', 'dpaa', 'opaa'].includes(name) ? undefined : AXIOM_NOTE)),
  ...TBOX.map(([name, description]) => fn('tbox', name, description, '?a, ?b', `tbox:${name}(?a, ?b)`, 'caution', AXIOM_NOTE)),
  ...RBOX.map(([name, description]) => fn('rbox', name, description, '?a, ?b', `rbox:${name}(?a, ?b)`, 'caution', AXIOM_NOTE)),
];

export const SWRL_BUILTINS: BuiltInCategory[] = [
  { prefix: 'swrlb', name: 'Comparisons', description: 'Compare two values', builtIns: COMPARE },
  { prefix: 'swrlb', name: 'Math', description: 'Arithmetic and rounding', builtIns: MATH },
  { prefix: 'swrlb', name: 'Text', description: 'Text matching and editing', builtIns: TEXT },
  { prefix: 'swrlb', name: 'Dates, times and durations', description: 'Calendar arithmetic', builtIns: DATE_TIME },
  { prefix: 'swrlb', name: 'Web addresses and lists', description: 'Listed by the SWRL standard but not implemented by the rule engine', builtIns: WEB_AND_LISTS },
  { prefix: 'swrlm', name: 'Extra math', description: 'Square root, logarithm and formulas', builtIns: EXTENSIONS },
  { prefix: 'swrlx', name: 'New individuals', description: 'Create new individuals from a rule', builtIns: INDIVIDUALS },
  { prefix: 'temporal', name: 'Time spans', description: 'How two time spans relate (before, overlaps, ...)', builtIns: temporalRelations },
  { prefix: 'temporal', name: 'Time amounts', description: 'Add time and measure the time between two date-times', builtIns: temporalDuration },
  { prefix: 'sqwrl', name: 'Query results', description: 'Choose, sort, count and slice the rows of a query', builtIns: SQWRL_RESULT },
  { prefix: 'sqwrl', name: 'Query collections', description: 'Sets and bags of values inside a query', builtIns: SQWRL_COLLECTIONS },
  { prefix: 'abox', name: 'Ontology facts', description: 'Query the individuals and the facts stated about them', builtIns: AXIOM_QUERIES.filter(item => item.fullName.startsWith('abox:')) },
  { prefix: 'tbox', name: 'Ontology classes', description: 'Query the class axioms', builtIns: AXIOM_QUERIES.filter(item => item.fullName.startsWith('tbox:')) },
  { prefix: 'rbox', name: 'Ontology properties', description: 'Query the property axioms', builtIns: AXIOM_QUERIES.filter(item => item.fullName.startsWith('rbox:')) },
];

export const BUILT_IN_PREFIXES = new Set(['swrlb', 'swrlm', 'swrlx', 'temporal', 'sqwrl', 'abox', 'tbox', 'rbox']);

export const SWRL_BUILTINS_QUICK = [
  { category: 'Compare', items: ['swrlb:equal', 'swrlb:notEqual', 'swrlb:lessThan', 'swrlb:greaterThan', 'swrlb:lessThanOrEqual', 'swrlb:greaterThanOrEqual'] },
  { category: 'Math', items: ['swrlb:add', 'swrlb:subtract', 'swrlb:multiply', 'swrlb:divide', 'swrlb:abs', 'swrlb:round'] },
  { category: 'String', items: ['swrlb:stringConcat', 'swrlb:contains', 'swrlb:startsWith', 'swrlb:endsWith', 'swrlb:matches'] },
];

export const allBuiltIns = (): BuiltInInfo[] => SWRL_BUILTINS.flatMap(category => category.builtIns);
