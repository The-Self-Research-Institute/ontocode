declare module 'sockjs-client' {
  export default class SockJS {
    constructor(url: string, _reserved?: any, options?: any);
    close(code?: number, reason?: string): void;
    send(data: string): void;
    onopen: ((e: Event) => void) | null;
    onclose: ((e: CloseEvent) => void) | null;
    onmessage: ((e: MessageEvent) => void) | null;
  }
}
