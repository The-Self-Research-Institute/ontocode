import React, { useEffect, useState } from "react";
import { AlertCircle, FileCode, FileText, Flag, ListOrdered } from "lucide-react";
import { useAuth } from "../custom-hook/useAuth";
import { isAppOnline, subscribeOnlineStatus } from "../utils/connectivity";
import { isDesktop } from "../utils/desktop";
import { validateEmail } from "../utils/validation";
import { submitIssueReport, SubmitResult } from "../utils/issueSubmit";
import type { FeedbackType } from "../utils/feedback";
import IssueAttachmentPicker from "./IssueAttachmentPicker";
import IssueSubmitBanner from "./IssueSubmitBanner";
import { INPUT_CLASS, IssueEmailField, IssueFormFooter, IssueFormHeader, IssueSidebar, LABEL_CLASS, PRIORITIES } from "./IssueFormParts";

const DESKTOP_PLACEHOLDER_EMAIL = "local@ontocode.desktop";

export type IssueKind = FeedbackType;

export interface IssueReportFormProps {
  kind: IssueKind;
  projectName?: string;
  projectId?: string;
  ontologyFilePath?: string;
  onBack: () => void;
  onSwitchKind: () => void;
}

export const IssueReportForm: React.FC<IssueReportFormProps> = ({
  kind,
  projectName,
  projectId,
  ontologyFilePath,
  onBack,
  onSwitchKind,
}) => {
  const { user } = useAuth();
  const desktop = isDesktop();
  const isFeature = kind === "feature";
  const signedIn = !!user?.token && !!user?.email && user.email !== DESKTOP_PLACEHOLDER_EMAIL;

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [stepsToReproduce, setStepsToReproduce] = useState("");
  const [priority, setPriority] = useState("Medium");
  const [attachments, setAttachments] = useState<File[]>([]);
  const [email, setEmail] = useState("");
  const [emailTouched, setEmailTouched] = useState(false);
  const [localProfile, setLocalProfile] = useState<Record<string, unknown> | null>(null);
  const [online, setOnline] = useState(isAppOnline);
  const [submitting, setSubmitting] = useState(false);
  const [submitResult, setSubmitResult] = useState<SubmitResult | null>(null);

  useEffect(() => {
    setOnline(isAppOnline());
    return subscribeOnlineStatus(setOnline);
  }, []);

  useEffect(() => {
    if (!desktop) {return;}
    (window as any).electronAPI
      ?.getProfile()
      .then((profile: Record<string, unknown> | null) => {
        setLocalProfile(profile);
        const savedEmail = typeof profile?.email === "string" ? profile.email : "";
        if (savedEmail) {setEmail(savedEmail);}
        else if (user?.email && user.email !== DESKTOP_PLACEHOLDER_EMAIL) {setEmail(user.email);}
      })
      .catch(() => undefined);
  }, [desktop]);

  const emailValidation = signedIn ? { isValid: true, error: undefined } : validateEmail(email);
  let blockReason: string | undefined;
  if (!online) {blockReason = "Connect to the internet to submit a report";}
  else if (!emailValidation.isValid) {blockReason = emailValidation.error || "Enter a valid email address";}
  else if (!title.trim()) {blockReason = "Enter a title";}
  else if (!description.trim()) {blockReason = "Enter a description";}

  const handleSubmit = async () => {
    if (blockReason) {
      setEmailTouched(true);
      return;
    }
    setSubmitting(true);
    setSubmitResult(null);
    const result = await submitIssueReport({
      kind,
      title,
      description,
      stepsToReproduce,
      priority,
      email: signedIn ? null : email.trim(),
      projectId,
      projectName,
      ontologyFilePath,
      attachments,
      token: user?.token,
    });
    setSubmitResult(result);
    setSubmitting(false);
    if (!result.success) {return;}
    if (desktop && !signedIn) {
      (window as any).electronAPI?.saveProfile({ ...localProfile, email: email.trim() }).catch(() => undefined);
    }
    if (!result.trackerFailureReason) {setTimeout(onBack, 3000);}
  };

  return (
    <div className="min-h-full bg-gray-50 flex flex-col">
      <IssueFormHeader kind={kind} projectName={projectName} busy={submitting} onBack={onBack} onSwitchKind={onSwitchKind} />

      <main className="flex-1 w-full max-w-6xl mx-auto px-6 py-8 grid gap-8 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-6 min-w-0">
          {!online && (
            <div className="px-4 py-3 rounded-lg border border-amber-300 bg-amber-50 text-amber-900 text-sm flex items-start gap-2">
              <AlertCircle size={18} className="flex-shrink-0 mt-0.5" />
              <span>
                <strong>Offline.</strong> Reports are sent to our servers when you are online.
                {desktop ? " Your ontology work continues locally." : " Reconnect, then submit."}
              </span>
            </div>
          )}

          {submitResult && <IssueSubmitBanner result={submitResult} kind={kind} priority={priority} />}

          <div className="rounded-xl border border-gray-200 bg-white p-6 space-y-6">
            <div className="space-y-2">
              <label className={LABEL_CLASS}>
                <FileText size={16} className="text-purple-600" />
                Title <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder={isFeature ? "Brief summary of the feature" : "Brief summary of the issue"}
                maxLength={200}
                className={INPUT_CLASS}
                disabled={submitting}
              />
              <div className="flex justify-between items-center">
                <p className="text-xs text-gray-500">Provide a clear, concise summary</p>
                <p className={`text-xs font-medium ${title.length > 180 ? "text-orange-600" : "text-gray-500"}`}>{title.length}/200</p>
              </div>
            </div>

            <div className="space-y-2">
              <label className={LABEL_CLASS}>
                <FileCode size={16} className="text-purple-600" />
                {isFeature ? "What problem would this solve?" : "Description"} <span className="text-red-500">*</span>
              </label>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={8}
                placeholder={
                  isFeature
                    ? "Describe what you are trying to do, what is hard today, and how you would like it to work..."
                    : "Describe the issue in detail. Include what you expected vs. what actually happened..."
                }
                className={`${INPUT_CLASS} resize-y`}
                disabled={submitting}
              />
            </div>

            {!isFeature && (
              <div className="space-y-2">
                <label className={LABEL_CLASS}>
                  <ListOrdered size={16} className="text-purple-600" />
                  Steps to Reproduce
                  <span className="text-xs font-normal text-gray-500">(Optional)</span>
                </label>
                <textarea
                  value={stepsToReproduce}
                  onChange={(e) => setStepsToReproduce(e.target.value)}
                  rows={6}
                  placeholder={"1. Go to...\n2. Click on...\n3. See error..."}
                  className={`${INPUT_CLASS} resize-y`}
                  disabled={submitting}
                />
              </div>
            )}
          </div>

          <div className="rounded-xl border border-gray-200 bg-white p-6">
            <IssueAttachmentPicker attachments={attachments} onChange={setAttachments} disabled={submitting} />
          </div>
        </div>

        <IssueSidebar kind={kind} projectName={projectName} ontologyFilePath={ontologyFilePath}>
          {!isFeature && (
            <div className="space-y-2">
              <label className={LABEL_CLASS}>
                <Flag size={16} className="text-purple-600" />
                Priority <span className="text-red-500">*</span>
              </label>
              <select value={priority} onChange={(e) => setPriority(e.target.value)} className={`${INPUT_CLASS} cursor-pointer`} disabled={submitting}>
                {PRIORITIES.map((p) => (
                  <option key={p.value} value={p.value}>{p.label}</option>
                ))}
              </select>
            </div>
          )}
          <IssueEmailField
            signedIn={signedIn}
            signedInEmail={user?.email}
            email={email}
            error={emailTouched && !emailValidation.isValid ? emailValidation.error : undefined}
            disabled={submitting}
            onChange={setEmail}
            onBlur={() => setEmailTouched(true)}
          />
        </IssueSidebar>
      </main>

      <IssueFormFooter kind={kind} submitting={submitting} blockReason={blockReason} onBack={onBack} onSubmit={handleSubmit} />
    </div>
  );
};

export default IssueReportForm;
