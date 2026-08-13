export type OpenSourceLibrary = {
  name: string;
  license: string;
  url?: string;
  usedIn: string;
};

export const OPEN_SOURCE_LIBRARIES: OpenSourceLibrary[] = [

  { name: "React", license: "MIT", url: "https://react.dev/", usedIn: "Web / Desktop UI" },
  { name: "Vite", license: "MIT", url: "https://vitejs.dev/", usedIn: "Web / Desktop UI build" },
  { name: "TypeScript", license: "Apache-2.0", url: "https://www.typescriptlang.org/", usedIn: "Web / Desktop UI" },
  { name: "Tailwind CSS", license: "MIT", url: "https://tailwindcss.com/", usedIn: "Web / Desktop UI" },
  { name: "Axios", license: "MIT", url: "https://axios-http.com/", usedIn: "Web / Desktop API client" },
  { name: "@stomp/stompjs", license: "Apache-2.0", url: "https://stomp-js.github.io/", usedIn: "Real-time collaboration" },
  { name: "SockJS Client", license: "MIT", url: "https://github.com/sockjs/sockjs-client", usedIn: "WebSocket transport" },
  { name: "ws", license: "MIT", url: "https://github.com/websockets/ws", usedIn: "VSCode extension WebSocket" },
  { name: "form-data", license: "MIT", url: "https://github.com/form-data/form-data", usedIn: "Multipart HTTP requests" },
  { name: "dotenv", license: "BSD-2-Clause", url: "https://github.com/motdotla/dotenv", usedIn: "VSCode extension config" },
  { name: "fs-extra", license: "MIT", url: "https://github.com/jprichardson/node-fs-extra", usedIn: "VSCode extension file I/O" },
  { name: "crypto-browserify", license: "MIT", url: "https://github.com/browserify/crypto-browserify", usedIn: "VSCode extension web bundle" },
  { name: "path-browserify", license: "MIT", url: "https://github.com/browserify/path-browserify", usedIn: "VSCode extension web bundle" },

  { name: "Lucide React", license: "ISC", url: "https://lucide.dev/", usedIn: "Web / Desktop UI icons" },
  { name: "D3.js", license: "ISC", url: "https://d3js.org/", usedIn: "Graph View plugin" },
  { name: "Vis Network", license: "Apache-2.0", url: "https://visjs.github.io/vis-network/", usedIn: "Ontology graph rendering" },
  { name: "@stripe/react-stripe-js", license: "MIT", url: "https://stripe.com/docs/stripe-js/react", usedIn: "Subscription billing UI" },
  { name: "@stripe/stripe-js", license: "MIT", url: "https://stripe.com/docs/js", usedIn: "Subscription billing UI" },

  { name: "Electron", license: "MIT", url: "https://www.electronjs.org/", usedIn: "Desktop app shell" },
  { name: "electron-updater", license: "MIT", url: "https://www.electron.build/auto-update", usedIn: "Desktop auto-update" },
  { name: "electron-store", license: "MIT", url: "https://github.com/sindresorhus/electron-store", usedIn: "Desktop persistent storage" },

  { name: "Spring Boot", license: "Apache-2.0", url: "https://spring.io/projects/spring-boot", usedIn: "Auth, Editor, Gateway, Plugin services" },
  { name: "Spring Cloud Gateway", license: "Apache-2.0", url: "https://spring.io/projects/spring-cloud-gateway", usedIn: "API Gateway" },
  { name: "Spring Security", license: "Apache-2.0", url: "https://spring.io/projects/spring-security", usedIn: "Auth / Editor / Gateway" },
  { name: "Spring Data MongoDB", license: "Apache-2.0", url: "https://spring.io/projects/spring-data-mongodb", usedIn: "Auth / Editor data layer" },
  { name: "JJWT", license: "Apache-2.0", url: "https://github.com/jwtk/jjwt", usedIn: "JWT authentication" },

  { name: "OWL API", license: "LGPL-2.1+", url: "https://github.com/owlcs/owlapi", usedIn: "Ontology editing & reasoning" },
  { name: "Eclipse RDF4J", license: "BSD-3-Clause", url: "https://rdf4j.org/", usedIn: "SPARQL queries & RDF I/O" },
  { name: "HermiT Reasoner", license: "LGPL-2.1", url: "http://www.hermit-reasoner.com/", usedIn: "OWL 2 DL reasoner" },
  { name: "JFact Reasoner", license: "LGPL-2.1", url: "http://jfact.sourceforge.net/", usedIn: "OWL 2 DL reasoner" },
  { name: "ELK Reasoner", license: "LGPL-2.1+", url: "https://github.com/liveontologies/elk-reasoner", usedIn: "OWL 2 EL reasoner" },
  { name: "Openllet (Pellet)", license: "AGPL-3.0", url: "https://github.com/Galigator/openllet", usedIn: "OWL 2 DL reasoner" },
  { name: "SWRL API", license: "BSD-3-Clause", url: "https://github.com/protegeproject/swrlapi", usedIn: "SWRL rules support" },

  { name: "Apache Jena / Fuseki", license: "Apache-2.0", url: "https://jena.apache.org/", usedIn: "SPARQL & RDF store (desktop)" },
  { name: "MongoDB", license: "SSPL", url: "https://www.mongodb.com/", usedIn: "Metadata & collaboration storage" },
  { name: "GraphDB (Ontotext)", license: "Commercial / Free tier", url: "https://www.ontotext.com/products/graphdb/", usedIn: "RDF triple store (cloud)" },

  { name: "Stripe Java Client", license: "MIT", url: "https://github.com/stripe/stripe-java", usedIn: "Subscription billing backend" },
  { name: "Bucket4J", license: "Apache-2.0", url: "https://github.com/bucket4j/bucket4j", usedIn: "API rate limiting" },

  { name: "Gson", license: "Apache-2.0", url: "https://github.com/google/gson", usedIn: "JSON serialization (editor)" },
  { name: "Apache Commons IO", license: "Apache-2.0", url: "https://commons.apache.org/proper/commons-io/", usedIn: "File utilities (editor)" },
  { name: "Caffeine", license: "Apache-2.0", url: "https://github.com/ben-manes/caffeine", usedIn: "In-process caching" },
  { name: "Lombok", license: "MIT", url: "https://projectlombok.org/", usedIn: "Java boilerplate reduction (all services)" },
];
