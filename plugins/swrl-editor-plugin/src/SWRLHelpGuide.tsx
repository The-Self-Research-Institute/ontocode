import React, { useEffect } from 'react';
import { GuideCard, GuideSectionLabel, GuideShell } from './ui/GuideShell';

interface SWRLHelpGuideProps {
  isOpen: boolean;
  onClose: () => void;
  focusCard?: string;
}

const COPY = {
  title: 'SWRL Guide',
  intro: 'Rules work out new facts from what is already in your ontology. Nothing is saved until you add the results.',
  gettingStarted: 'Getting started',
  steps: [
    'Write a rule, or pick one from the templates.',
    'Run it on the Run and results tab.',
    'Read the new facts it found.',
    'Add the ones you want to your ontology.',
  ],
  whatYouCanDo: 'What you can do',
  problems: 'If something goes wrong',
  seen: 'What you see',
  todo: 'What to do',
};

const CARDS: Array<{ id?: string; title: string; example?: string; body: string }> = [
  { title: 'Write a rule', example: 'Customer(?c) ^ numberOfPizzasPurchased(?c, ?n) ^ swrlb:greaterThan(?n, 1) -> hasDiscount(?c, 0.2)', body: 'A rule says: if everything on the left is true, then the right side is true too. Use New rule on the Rules tab, then Save. Try it checks the rule on its own.' },
  { title: 'Run and read the results', example: '118 new facts found in 4.8 s, from 7 rules', body: 'Press Run rules. Leave all rules unticked to run every enabled rule, or choose which rules to run.' },
  { title: 'Add facts to your ontology', example: 'Add 118 facts to the ontology…', body: 'You always get a confirmation first. The facts become ordinary statements and you can undo them from the history.' },
  { title: 'Ask a question (query)', example: 'Person(?p) -> sqwrl:count(?p)', body: 'Open More, then Ask a question (query), to count or list things in your ontology as a table.' },
  { title: 'See what standard reasoning adds', body: 'Open More, then Standard reasoning, to see facts the ontology already implies without any rule. On the results screen, the Standard reasoning choice lists the same kind of facts for your last run.' },
  { id: 'swrl-guide-explained', title: 'How facts are explained', body: 'Point at a fact from your rules and a ? appears. Click it to see which rule produced the fact and which values it used. Facts from standard reasoning have no rule behind them, and that is not an error.' },
  { title: 'Look up a function', body: 'Open More, then Function help, for the functions a rule can use, such as comparing numbers.' },
];

const PROBLEMS: Array<{ message: string; action: string }> = [
  { message: 'Skipped: This rule is disabled.', action: 'Turn the rule on in the Rules tab, then run again.' },
  { message: 'Skipped: This is a SQWRL query. It is not run as a rule.', action: 'Use More, then Ask a question (query), to run it.' },
  { message: 'Not available (in Function help)', action: 'The rule engine cannot run that function. Pick another function from the list.' },
  { message: 'This ontology is too large to compute the OWL 2 RL closure.', action: 'Standard reasoning only works on smaller ontologies. Run your rules instead.' },
  { message: 'None of your rules produced this fact.', action: 'It comes from standard reasoning, so it is not an error. Nothing to fix.' },
  { message: 'The server is busy with other work. Try again in a moment.', action: 'Wait a few seconds and press the button again.' },
];

const SWRLHelpGuide: React.FC<SWRLHelpGuideProps> = ({ isOpen, onClose, focusCard = '' }) => {
  useEffect(() => {
    if (!isOpen || !focusCard) return;
    const el = document.getElementById(`swrl-guide-${focusCard}`);
    if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'center' });
  }, [isOpen, focusCard]);

  return (
    <GuideShell isOpen={isOpen} onClose={onClose} title={COPY.title} testId="swrl-guide">
      <p className="text-sm text-gray-600">{COPY.intro}</p>

      <div>
        <GuideSectionLabel>{COPY.gettingStarted}</GuideSectionLabel>
        <ol className="text-sm text-gray-700 space-y-1.5 list-decimal list-inside">
          {COPY.steps.map(step => <li key={step}>{step}</li>)}
        </ol>
      </div>

      <div>
        <GuideSectionLabel>{COPY.whatYouCanDo}</GuideSectionLabel>
        <div className="flex flex-col gap-3">
          {CARDS.map(card => (
            <GuideCard key={card.title} id={card.id} title={card.title} example={card.example}>{card.body}</GuideCard>
          ))}
        </div>
      </div>

      <div>
        <GuideSectionLabel>{COPY.problems}</GuideSectionLabel>
        <div className="overflow-x-auto">
          <table className="w-full text-xs text-left border border-gray-200 rounded">
            <thead>
              <tr className="bg-gray-50 text-gray-500">
                <th className="p-2 font-semibold">{COPY.seen}</th>
                <th className="p-2 font-semibold">{COPY.todo}</th>
              </tr>
            </thead>
            <tbody>
              {PROBLEMS.map(p => (
                <tr key={p.message} className="border-t border-gray-200 align-top">
                  <td className="p-2 text-gray-800">{p.message}</td>
                  <td className="p-2 text-gray-700">{p.action}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </GuideShell>
  );
};

export default SWRLHelpGuide;
