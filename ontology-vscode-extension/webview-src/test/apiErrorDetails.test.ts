import { describe, expect, it } from 'vitest';
import { apiErrorDetails } from '../utils/apiErrorDetails';

describe('apiErrorDetails', () => {
  it('reads the status and body the app API client puts on its errors', () => {
    expect(apiErrorDetails({ status: 409, data: { error: 'No' } })).toEqual({ status: 409, data: { error: 'No' } });
  });

  it('also reads the nested response shape', () => {
    expect(apiErrorDetails({ response: { status: 500, data: { error: 'Bad' } } })).toEqual({ status: 500, data: { error: 'Bad' } });
  });

  it('returns nothing for errors without an HTTP answer', () => {
    expect(apiErrorDetails(new Error('network'))).toEqual({ status: undefined, data: undefined });
    expect(apiErrorDetails(null)).toEqual({ status: undefined, data: undefined });
    expect(apiErrorDetails({ status: 502, data: 'Bad Gateway' })).toEqual({ status: 502, data: undefined });
  });
});
