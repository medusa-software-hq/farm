import { createClient } from '@connectrpc/connect';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import { FarmService } from './gen/medusa/farm/v1/farm_service_pb.ts';

const API_URL = import.meta.env.VITE_API_URL as string;

if (!API_URL) {
  throw new Error('VITE_API_URL is not set');
}

/** The one FarmService client, shared across routes. */
export const farm = createClient(FarmService, createGrpcWebTransport({ baseUrl: API_URL }));
