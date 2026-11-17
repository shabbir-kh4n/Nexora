type Matcher = (query: string) => boolean;
type Listener = (event: MediaQueryListEvent) => void;

interface Registration {
  query: string;
  matches: boolean;
  listeners: Set<Listener>;
}

// jsdom has no matchMedia. `update` swaps the rule (a resize or a colour-scheme change) and notifies
// only the queries whose result actually changed, like a browser does.
export function installMatchMedia(initial: Matcher) {
  let matcher = initial;
  const registrations: Registration[] = [];

  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    writable: true,
    value: (query: string): MediaQueryList => {
      const registration: Registration = { query, matches: matcher(query), listeners: new Set() };
      registrations.push(registration);
      return {
        get matches() {
          return registration.matches;
        },
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: Listener) => registration.listeners.add(listener),
        removeEventListener: (_type: string, listener: Listener) => registration.listeners.delete(listener),
        addListener: (listener: Listener) => registration.listeners.add(listener),
        removeListener: (listener: Listener) => registration.listeners.delete(listener),
        dispatchEvent: () => false,
      } as MediaQueryList;
    },
  });

  return {
    update(next: Matcher) {
      matcher = next;
      for (const registration of registrations) {
        const matches = matcher(registration.query);
        if (matches === registration.matches) continue;
        registration.matches = matches;
        const event = { matches, media: registration.query } as MediaQueryListEvent;
        registration.listeners.forEach((listener) => listener(event));
      }
    },
  };
}
