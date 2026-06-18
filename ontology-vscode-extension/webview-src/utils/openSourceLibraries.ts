export type OpenSourceLibrary = {
  name: string;
  license: string;
  url?: string;
  usedIn: string;
};

/** Key open-source dependencies used across OntoCode (web, desktop, backend). */
export const OPEN_SOURCE_LIBRARIES: OpenSourceLibrary[] = [
  { name: "React", license: "MIT", url: "https://react.dev/", usedIn: "Web / Desktop UI" },
  { name: "Vite", license: "MIT", url: "https://vitejs.dev/", usedIn: "Web / Desktop UI build" },
  { name: "TypeScript", license: "Apache-2.0", url: "https://www.typescriptlang.org/", usedIn: "Web / Desktop UI" },
  { name: "Tailwind CSS", license: "MIT", url: "https://tailwindcss.com/", usedIn: "Web / Desktop UI" },
  { name: "Lucide React", license: "ISC", url: "https://lucide.dev/", usedIn: "Web / Desktop UI icons" },
  { name: "D3.js", license: "ISC", url: "https://d3js.org/", usedIn: "Graph View plugin" },
  { name: "Axios", license: "MIT", url: "https://axios-http.com/", usedIn: "Web / Desktop API client" },
  { name: "@stomp/stompjs", license: "Apache-2.0", url: "https://stomp-js.github.io/", usedIn: "Real-time collaboration" },
  { name: "SockJS Client", license: "MIT", url: "https://github.com/sockjs/sockjs-client", usedIn: "WebSocket transport" },
  { name: "Electron", license: "MIT", url: "https://www.electronjs.org/", usedIn: "Desktop app shell" },
  { name: "electron-updater", license: "MIT", url: "https://www.electron.build/auto-update", usedIn: "Desktop auto-update" },
  { name: "Spring Boot", license: "Apache-2.0", url: "https://spring.io/projects/spring-boot", usedIn: "Auth, Editor, Gateway services" },
  { name: "OWL API", license: "BSD-3-Clause", url: "https://github.com/owlcs/owlapi", usedIn: "Ontology editing & reasoning" },
  { name: "HermiT", license: "LGPL-3.0", url: "http://www.hermit-reasoner.com/", usedIn: "OWL 2 DL reasoner" },
  { name: "ELK Reasoner", license: "Apache-2.0", url: "https://github.com/liveontologies/elk-reasoner", usedIn: "OWL 2 EL reasoner" },
  { name: "Openllet", license: "AGPL-3.0", url: "https://github.com/pelletier/openllet", usedIn: "OWL 2 DL reasoner" },
  { name: "Apache Jena / Fuseki", license: "Apache-2.0", url: "https://jena.apache.org/", usedIn: "SPARQL & RDF store (desktop)" },
  { name: "MongoDB", license: "SSPL", url: "https://www.mongodb.com/", usedIn: "Metadata & collaboration storage" },
  { name: "GraphDB (Ontotext)", license: "Commercial / Free tier", url: "https://www.ontotext.com/products/graphdb/", usedIn: "RDF triple store (cloud)" },
  { name: "Stripe.js", license: "MIT", url: "https://stripe.com/docs/js", usedIn: "Subscription billing UI" },
];
