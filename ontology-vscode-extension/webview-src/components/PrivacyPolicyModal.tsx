import React from "react";
import { X, Shield } from "lucide-react";

interface Props {
  isOpen: boolean;
  onClose: () => void;
}

const EFFECTIVE_DATE = "September 15, 2026";
const COMPANY = "The Self Research Institute";
const PRODUCT = "OntoCode Studio";
const CONTACT = "privacy@ontocode.org";

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
            <h3 className="font-semibold text-gray-900 mb-1">3. The Desktop Application</h3>
            <p>
              {PRODUCT}'s desktop application does not currently require or support any account or sign-in —
              it operates as a local, standalone tool with no personal identity attached. Your ontology
              projects, and the bundled MongoDB and Apache Jena Fuseki data stores used to power editing and
              reasoning, are stored on your own device.
            </p>
            <p>The desktop application does make a small number of network calls:</p>
            <ul className="list-disc list-inside space-y-1 pl-2">
              <li>If installed via a direct download (not through Microsoft Store), an automatic update and version check against our own download servers, so the app can offer you new releases. Store installs are updated by Microsoft Store instead, and never contact our download servers for this purpose.</li>
              <li>If you use the in-app "Report Issue" feature, the description and diagnostic details you submit are sent to our servers and logged in our internal issue tracker so our team can act on them.</li>
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
              We retain your account data for as long as your account is active. When you delete your account,
              your account record is deleted immediately, and any project or workspace data you solely owned
              is permanently removed within 30 days, subject to legal obligations. Hashed download-page
              analytics (which cannot be traced back to you) may be retained indefinitely.
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
              personal data, or to restrict or object to certain processing. To exercise these rights, contact
              us at <span className="text-indigo-600 font-medium">{CONTACT}</span>.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">10. Cookies and Local Storage</h3>
            <p>
              The desktop application and the {PRODUCT} VS Code extension do not use browser cookies. The
              web application and the desktop application's embedded interface may use session tokens and
              local storage for authentication purposes when you sign in.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">11. Changes to This Policy</h3>
            <p>
              We may update this Privacy Policy from time to time. We will notify you of significant changes
              by email and will always display the effective date at the top of this document.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">12. Contact Us</h3>
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
