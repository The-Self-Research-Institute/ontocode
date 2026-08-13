import React from "react";
import { X, FileText } from "lucide-react";

interface Props {
  isOpen: boolean;
  onClose: () => void;
}

const EFFECTIVE_DATE = "To be announced";
const COMPANY = "The Self Research Institute";
const PRODUCT = "OntoCode";
const CONTACT = "legal@ontocode.org";

export const TermsAndConditionsModal: React.FC<Props> = ({ isOpen, onClose }) => {
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
        aria-labelledby="tnc-title"
        onClick={(e) => e.stopPropagation()}
      >
        {}
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200 shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 bg-purple-100 rounded-lg flex items-center justify-center">
              <FileText className="w-5 h-5 text-purple-600" />
            </div>
            <div>
              <h2 id="tnc-title" className="text-lg font-semibold text-gray-900">
                Terms and Conditions
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

        {}
        <div className="overflow-y-auto px-6 py-5 text-sm text-gray-700 space-y-5 leading-relaxed">
          <div className="bg-amber-50 border border-amber-200 rounded-lg px-4 py-3 text-amber-800 text-xs">
            These Terms and Conditions are currently being finalised and will be published before the official
            launch of {PRODUCT}. By creating an account during the beta period you acknowledge that these terms
            may change before the final release.
          </div>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">1. Acceptance of Terms</h3>
            <p>
              By accessing or using {PRODUCT}, a product of {COMPANY}, you agree to be bound by these Terms and
              Conditions and all applicable laws and regulations. If you do not agree with any part of these
              terms, you may not use the service.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">2. Description of Service</h3>
            <p>
              {PRODUCT} is a collaborative OWL ontology editor and knowledge-graph platform. It provides tools
              for building, editing, reasoning over, and sharing ontologies. Features and plans are subject to
              change.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">3. User Accounts</h3>
            <p>
              You are responsible for maintaining the confidentiality of your account credentials and for all
              activity that occurs under your account. You must notify us immediately of any unauthorised use.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">4. Acceptable Use</h3>
            <p>
              You agree not to use {PRODUCT} to upload, store, or share content that is unlawful, harmful,
              defamatory, or infringes any third-party intellectual property rights. Automated scraping,
              reverse-engineering, or abuse of the platform APIs is prohibited.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">5. Intellectual Property</h3>
            <p>
              All ontology data you create and upload remains your intellectual property. You grant {COMPANY} a
              limited licence to store and process that data solely for the purpose of providing the service.
              {PRODUCT}'s software, branding, and documentation remain the property of {COMPANY}.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">6. Open Source Components</h3>
            <p>
              The {PRODUCT} VSCode extension is licensed under the GNU Affero General Public License v3 (AGPL-3.0-or-later).
              Other components of the platform may have different licensing terms, which will be documented
              separately.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">7. Subscriptions and Billing</h3>
            <p>
              Paid plans are billed in advance on a monthly or annual basis. Refunds, cancellations, and trial
              eligibility are governed by the plan details presented at the time of subscription. Enterprise
              plans do not include a free trial period.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">8. Disclaimer of Warranties</h3>
            <p>
              {PRODUCT} is provided "as is" without warranties of any kind, either express or implied. We do
              not warrant that the service will be uninterrupted, error-free, or free of harmful components.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">9. Limitation of Liability</h3>
            <p>
              To the maximum extent permitted by law, {COMPANY} shall not be liable for any indirect,
              incidental, special, or consequential damages arising from your use of {PRODUCT}.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">10. Changes to Terms</h3>
            <p>
              We reserve the right to update these Terms at any time. Continued use of the service after
              changes are posted constitutes acceptance of the revised Terms. We will notify registered users
              of material changes by email.
            </p>
          </section>

          <section>
            <h3 className="font-semibold text-gray-900 mb-1">11. Contact</h3>
            <p>
              For questions about these Terms, please contact us at{" "}
              <span className="text-purple-600 font-medium">{CONTACT}</span>.
            </p>
          </section>
        </div>

        {}
        <div className="px-6 py-4 border-t border-gray-200 flex justify-end shrink-0">
          <button
            type="button"
            onClick={onClose}
            className="px-5 py-2 bg-purple-600 hover:bg-purple-700 text-white text-sm font-medium rounded-lg transition-colors"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};

export default TermsAndConditionsModal;
