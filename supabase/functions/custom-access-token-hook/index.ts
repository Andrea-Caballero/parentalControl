// T14: Custom Access Token Hook
// Registra en: Authentication → Hooks → Create new hook
// Tipo: Verify JWT → Custom access token hook
// Language: Typescript

// Este hook copia app_metadata.device_id al claim de primer nivel 'device_id'

import { Database } from '../src/lib/database.types'

interface HookEvent {
  type: 'verify-jwt'
  event: {
    jwt: {
      sub: string
      role: string
      aud: string
      exp: number
      iat: number
      email?: string
      app_metadata: {
        provider?: string
        providers?: string[]
        device_id?: string
        [key: string]: unknown
      }
      user_metadata?: {
        name?: string
        [key: string]: unknown
      }
      [key: string]: unknown
    }
    claims: {
      role: string
      [key: string]: unknown
    }
  }
}

export async function customAccessTokenHook(event: HookEvent): Promise<{ role?: string; [key: string]: unknown }> {
  const { jwt } = event.event

  // Copiar device_id de app_metadata a claim de primer nivel
  if (jwt.app_metadata?.device_id) {
    return {
      ...event.event.claims,
      device_id: jwt.app_metadata.device_id,
      // Mantener otros claims necesarios
      iss: 'supabase',
     iat: jwt.iat,
      exp: jwt.exp,
    }
  }

  // Si no hay device_id, devolver claims originales
  return event.event.claims
}

// Ejemplo de JWT resultante después del hook:
//
// Payload:
// {
//   "role": "authenticated",
//   "device_id": "uuid-del-dispositivo",  <-- Copiado del app_metadata
//   "sub": "user-uuid",
//   "email": "device@example.com",
//   "iat": 1234567890,
//   "exp": 1234571490
// }

// Este device_id se puede acceder con:
// auth.jwt() ->> 'device_id' en RLS policies
