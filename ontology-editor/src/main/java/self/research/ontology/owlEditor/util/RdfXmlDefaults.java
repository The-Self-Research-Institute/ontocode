package self.research.ontology.owlEditor.util;

public final class RdfXmlDefaults {

    public static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";

    private RdfXmlDefaults() {
    }

    public static void apply() {
        if (System.getProperty(DISALLOW_DOCTYPE) == null) {
            System.setProperty(DISALLOW_DOCTYPE, "false");
        }
    }
}
