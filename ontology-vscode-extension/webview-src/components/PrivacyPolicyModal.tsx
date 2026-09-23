import React from "react";
import { X, Shield } from "lucide-react";

interface Props {
  isOpen: boolean;
  onClose: () => void;
}

const EFFECTIVE_DATE = "September 15, 2026";
const COMPANY = "The Self Research Institute";
const PRODUCT = "OntoCode Studio";
const CONTACT = "support@selfresearch.org";

export const PrivacyPolicyModal: React.FC<Props> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 z-[10000] flex items-center justify-center bg-black/60 p-4"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) onClose();
      }}
    >
      <div
        className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl max-h-[85vh] flex flex-col"
        role="dialog"
        aria-labelledby="privacy-title"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200 shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 bg-indigo-100 rounded-lg flex items-center justify-center">
              <Shield className="w-5 h-5 text-indigo-600" />
            </div>
            <div>
              <h2 id="privacy-title" className="text-lg font-semibold text-gray-900">
                Privacy Policy
              </h2>
              <p className="text-xs text-gray-500">Effective date: {EFFECTIVE_DATE}</p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 p-1 rounded-lg hover:bg-gray-100"
            aria-label="Close"
          >
            <X size={20} />
          </button>
        </div>

        {/* Body */}
        <div className="overflow-y-auto px-6 py-5 text-sm text-gray-700 space-y-5 leading-relaxed">
          <section>
            <h3 className="font-semibold text-gray-900 mb-1">1. Who We Are</h3>
            <p>
              {COMPANY} operates {PRODUCT}, a collaborative ontology editing and knowledge-graph platform. We
              are the data controller for personal information collected through the service.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">2. Information We Collect</h3>
            <ul className="list-disc list-inside space-y-1 pl-2">
              <li><span className="font-medium">Account data:</span> username, email address, and encrypted password hash.</li>
              <li><span className="font-medium">Billing data:</span> subscription plan, payment method details (processed securely via Stripe — we do not store full card numbers).</li>
              <li><span className="font-medium">Usage data:</span> ontology files you upload, project activity, and collaboration events.</li>
              <li><span className="font-medium">Technical data:</span> a one-way hashed version of your IP address, browser/app version, and referrer — collected when you visit our desktop download page — plus error logs used to diagnose crashes. We do not store your raw IP address.</li>
            </ul>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">3. The Desktop Application, VS Code Extension, and Web Application</h3>
            <p>
              The {PRODUCT} VS Code extension and the web application both support either a self-hosted
              deployment or our cloud-hosted service, configured per installation. If you use the cloud-hosted
              service, your account and identity data is maintained as described in Section 2, and your
              ontology edits, project data, and collaboration events (presence, cursor position, edit locks)
              are synced to our servers in real time — the same usage data and ontology data handling already
              described in Sections 2 and 6. If you self-host instead, that data stays on your own
              infrastructure.
            </p>
            <p>
              In both the VS Code extension and the web application: if you use the in-app "Report Issue"
              feature, the description, diagnostic details, and system information you submit are sent to our
              servers and logged in our internal issue tracker so our team can act on them. If you use the
              citation-insertion feature, the DOI you enter is sent to our backend to be validated against the
              official DOI registry (doi.org) on your behalf. If you optionally connect your own Zotero account
              by providing a Zotero API key, the application communicates directly with Zotero's API using that
              key to retrieve your citation library, and stores that key in your browser's local storage — this
              connection is between your device and Zotero directly; we do not see or store your Zotero data.
              If you enable the optional AI Insights feature, see Section 6 for how that data flows directly to
              your chosen third-party provider.
            </p>
            <p>
              The desktop application does not currently require or support any account or sign-in, and does
              not sync ontology data to our servers — it operates as a local, standalone tool. Your ontology
              projects, and the bundled MongoDB and Apache Jena Fuseki data stores used to power editing and
              reasoning, are stored on your own device. The desktop application does make a small number of
              network calls:
            </p>
            <ul className="list-disc list-inside space-y-1 pl-2">
              <li>If installed via a direct download (not through Microsoft Store), an automatic update and version check against our own download servers, so the app can offer you new releases. Store installs are updated by Microsoft Store instead, and never contact our download servers for this purpose.</li>
              <li>The same "Report Issue" and optional Zotero-connection behavior described above — these work identically to the VS Code extension and web application.</li>
            </ul>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">4. How We Use Your Information</h3>
            <ul className="list-disc list-inside space-y-1 pl-2">
              <li>Providing, maintaining, and improving {PRODUCT}.</li>
              <li>Processing payments and managing subscriptions.</li>
              <li>Sending transactional emails (account verification, billing receipts, security alerts).</li>
              <li>Responding to support requests.</li>
              <li>Privacy-friendly, hashed analytics on our download page, to understand which platforms and versions people are downloading.</li>
            </ul>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">5. Data Sharing</h3>
            <p>
              We do not sell your personal data. We share data only with trusted service providers necessary to
              operate the platform (e.g. Stripe for billing, Google for transactional email delivery, and
              cloud infrastructure providers) and only to the extent required to deliver those services. All
              providers are contractually bound to handle your data securely.
            </p>
            <p>
              California residents: we do not sell or share personal information as those terms are defined
              under the CCPA, so no opt-out mechanism is required. California residents may still exercise the
              access, deletion, and correction rights described in Section 9 by contacting us at the address
              below.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">6. Ontology Data</h3>
            <p>
              The ontology files and knowledge graphs you create belong to you. We store them to provide the
              service and, where you choose to collaborate, to share them with your team members. We do not
              use your ontology content for training AI models or for any purpose beyond providing the service.
            </p>
            <p>
              If you enable the optional AI Insights feature and provide your own API key for a third-party AI
              provider (Anthropic, OpenAI, or Google), portions of your ontology structure are sent directly
              from your device to that provider to generate insights and answers. This happens directly between
              your device and the provider you choose — we never see your API key or that data, and its
              handling is governed by your chosen provider's own terms and privacy policy.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">7. Data Retention</h3>
            <p>
              We retain your account data for as long as your account is active. When you delete your account:
              your account record and any issue-tracker submissions you filed are deleted immediately; projects
              and workspaces you solely own, with no other collaborators, are permanently deleted immediately,
              including their ontology content and files; and for anything you own that still has other
              collaborators, you choose at deletion time to transfer ownership to one of them or delete it
              anyway — if you choose to delete it, it is flagged for deletion and permanently removed within 30
              days, subject to legal obligations. Hashed download-page analytics (which cannot be traced back to
              you) may be retained indefinitely.
            </p>
            <p>
              Outside of account deletion, support requests and issue-tracker submissions (including
              diagnostic logs and system information) are automatically deleted after 24 months from
              submission. If a report was also forwarded to our issue tracker (OpenProject), that copy follows our
              issue-tracker provider's own retention practices. You can request earlier deletion of your
              reports at any time — see Section 9.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">8. Security</h3>
            <p>
              We implement industry-standard security measures including encryption in transit (TLS) and at
              rest. Passwords are stored as salted hashes and never in plain text. Despite these measures, no
              system is completely secure, and we cannot guarantee absolute security.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">9. Your Rights</h3>
            <p>
              Depending on your jurisdiction, you may have the right to access, correct, or delete your
              personal data, or to restrict or object to certain processing.
            </p>
            <p>
              Where GDPR applies, our legal basis for processing is: performance of a contract (account and
              billing data), legitimate interest (hashed IP address, browser/app version, referrer, and error
              logs used for download-page analytics and crash diagnosis), and consent (optional features like
              AI Insights).
            </p>
            <p>
              To exercise these rights, contact us at{" "}
              <span className="text-indigo-600 font-medium">{CONTACT}</span>.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">10. Cookies and Local Storage</h3>
            <p>
              The desktop application and the {PRODUCT} VS Code extension do not use browser cookies. The
              web application, and the embedded interface inside the desktop application and VS Code
              extension, may use session tokens and local storage for authentication purposes when you sign
              in, and to store your Zotero API key and configuration if you choose to connect a Zotero
              account.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">11. Governing Law</h3>
            <p>
              This Privacy Policy and any disputes arising from it are governed by the laws of the State of
              Oklahoma, USA, without regard to conflict-of-law principles.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">12. Changes to This Policy</h3>
            <p>
              We may update this Privacy Policy from time to time. We will notify you of significant changes
              by email and will always display the effective date at the top of this document.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">13. Contact Us</h3>
            <p>
              For privacy-related enquiries, please contact our data protection contact at{" "}
              <span className="text-indigo-600 font-medium">{CONTACT}</span>.
            </p>
          </section>
        </div>

        {/* Footer */}
        <div className="px-6 py-4 border-t border-gray-200 flex justify-end shrink-0">
          <button
            type="button"
            onClick={onClose}
            className="px-5 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium rounded-lg transition-colors"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};

export default PrivacyPolicyModal;
