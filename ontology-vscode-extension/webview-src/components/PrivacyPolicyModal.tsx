import React from "react";
import { X, Shield } from "lucide-react";

interface Props {
  isOpen: boolean;
  onClose: () => void;
}

const EFFECTIVE_DATE = "To be announced";
const COMPANY = "The Self Research Institute";
const PRODUCT = "OntoCode";
const CONTACT = "privacy@ontocode.org";

export const PrivacyPolicyModal: React.FC<Props> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-[10000] flex items-center justify-center bg-black/60 p-4" onClick={onClose}>
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
          <div className="bg-amber-50 border border-amber-200 rounded-lg px-4 py-3 text-amber-800 text-xs">
            This Privacy Policy is currently being finalised and will be published before the official launch of
            {" "}{PRODUCT}. During the beta period we are committed to handling your data responsibly in
            accordance with the principles outlined below.
          </div>

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
              <li><span className="font-medium">Technical data:</span> IP address, browser/extension version, and error logs used to maintain service quality.</li>
            </ul>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">3. How We Use Your Information</h3>
            <ul className="list-disc list-inside space-y-1 pl-2">
              <li>Providing, maintaining, and improving {PRODUCT}.</li>
              <li>Processing payments and managing subscriptions.</li>
              <li>Sending transactional emails (account verification, billing receipts, security alerts).</li>
              <li>Responding to support requests.</li>
              <li>Aggregated, anonymised analytics to understand usage patterns.</li>
            </ul>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">4. Data Sharing</h3>
            <p>
              We do not sell your personal data. We share data only with trusted service providers necessary to
              operate the platform (e.g. Stripe for billing, cloud infrastructure providers) and only to the
              extent required to deliver those services. All providers are contractually bound to handle your
              data securely.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">5. Ontology Data</h3>
            <p>
              The ontology files and knowledge graphs you create belong to you. We store them to provide the
              service and, where you choose to collaborate, to share them with your team members. We do not
              use your ontology content for training AI models or for any purpose beyond providing the service.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">6. Data Retention</h3>
            <p>
              We retain your account data for as long as your account is active. Upon account deletion, personal
              data is removed within 30 days, subject to legal obligations. Aggregated, anonymised analytics
              may be retained indefinitely.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">7. Security</h3>
            <p>
              We implement industry-standard security measures including encryption in transit (TLS) and at
              rest. Passwords are stored as salted hashes and never in plain text. Despite these measures, no
              system is completely secure, and we cannot guarantee absolute security.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">8. Your Rights</h3>
            <p>
              Depending on your jurisdiction, you may have the right to access, correct, or delete your
              personal data, or to restrict or object to certain processing. To exercise these rights, contact
              us at <span className="text-indigo-600 font-medium">{CONTACT}</span>.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">9. Cookies</h3>
            <p>
              The {PRODUCT} VSCode extension does not use browser cookies. The web-based components of the
              platform may use session tokens and local storage for authentication purposes. A full cookie
              policy will be published alongside this Privacy Policy before launch.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">10. Changes to This Policy</h3>
            <p>
              We may update this Privacy Policy from time to time. We will notify you of significant changes
              by email and will always display the effective date at the top of this document.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">11. Contact Us</h3>
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
