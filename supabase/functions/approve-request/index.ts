// T15: Approve Request - Edge Function
// Aprueba time_request, crea grant idempotente, sube versión, envía FCM

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // Solo padres pueden aprobar
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) {
      return new Response(
        JSON.stringify({ error: "Token requerido" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const token = authHeader.replace("Bearer ", "");
    const jwtPayload = JSON.parse(atob(token.split(".")[1]));
    const parentId = jwtPayload.sub;

    if (!parentId) {
      return new Response(
        JSON.stringify({ error: "Usuario no autenticado" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const { request_id, minutes, response_text } = await req.json();

    if (!request_id || !minutes) {
      return new Response(
        JSON.stringify({ error: "request_id y minutes son requeridos" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const supabaseAdmin = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    // 1. Verificar que la solicitud existe y pertenece a un dispositivo del padre
    const { data: timeRequest, error: requestError } = await supabaseAdmin
      .from("time_requests")
      .select("*, devices(parent_id)")
      .eq("id", request_id)
      .single();

    if (requestError || !timeRequest) {
      return new Response(
        JSON.stringify({ error: "Solicitud no encontrada" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Verificar propiedad
    if (timeRequest.devices?.parent_id !== parentId) {
      return new Response(
        JSON.stringify({ error: "No autorizado para aprobar esta solicitud" }),
        { status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 2. Verificar que no está ya aprobada (idempotencia)
    if (timeRequest.status === "APPROVED") {
      // Ya fue aprobada, buscar grant existente
      const { data: existingGrant } = await supabaseAdmin
        .from("grants")
        .select("*")
        .eq("request_id", request_id)
        .eq("source", "EXTRA_TIME")
        .single();

      return new Response(
        JSON.stringify({
          success: true,
          idempotent: true,
          grant_id: existingGrant?.id,
          message: "Solicitud ya aprobada anteriormente",
        }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 3. Actualizar estado de la solicitud
    await supabaseAdmin
      .from("time_requests")
      .update({
        status: "APPROVED",
        parent_response: response_text || null,
        responded_at: new Date().toISOString(),
      })
      .eq("id", request_id);

    // 4. Crear grant (idempotente por request_id único)
    const expiresAt = new Date();
    expiresAt.setMinutes(expiresAt.getMinutes() + minutes);

    const { data: grant, error: grantError } = await supabaseAdmin
      .from("grants")
      .insert({
        device_id: timeRequest.device_id,
        request_id: request_id,
        scope: timeRequest.package_name || "device",
        minutes: minutes,
        source: "EXTRA_TIME",
        status: "APPROVED",
        expires_at: expiresAt.toISOString(),
        granted_at: new Date().toISOString(),
      })
      .select()
      .single();

    if (grantError) {
      throw new Error(`Error creando grant: ${grantError.message}`);
    }

    // 5. Bump de versión (trigger automático en grants)
    // El trigger AFTER INSERT en grants ya llama a bump_policy_version

    // 6. Obtener versión actualizada
    const { data: device } = await supabaseAdmin
      .from("devices")
      .select("policy_version")
      .eq("id", timeRequest.device_id)
      .single();

    // 7. Enviar FCM al dispositivo
    await sendFcmToDevice(supabaseAdmin, timeRequest.device_id, {
      type: "POLICY_UPDATED",
      grant_id: grant.id,
      minutes: minutes,
      expires_at: expiresAt.toISOString(),
      new_policy_version: device?.policy_version,
    });

    return new Response(
      JSON.stringify({
        success: true,
        grant_id: grant.id,
        minutes: minutes,
        expires_at: expiresAt.toISOString(),
        policy_version: device?.policy_version,
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    console.error("Approve request error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

async function sendFcmToDevice(
  supabase: ReturnType<typeof createClient>,
  deviceId: string,
  payload: Record<string, unknown>
): Promise<void> {
  // Buscar token FCM activo del dispositivo
  const { data: tokenRecord } = await supabase
    .from("device_push_tokens")
    .select("token")
    .eq("device_id", deviceId)
    .eq("is_active", true)
    .limit(1)
    .single();

  if (!tokenRecord) {
    console.log("No FCM token for device:", deviceId);
    return;
  }

  const fcmUrl = "https://fcm.googleapis.com/fcm/send";
  const serverKey = Deno.env.get("FCM_SERVER_KEY");

  try {
    await fetch(fcmUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `key=${serverKey}`,
      },
      body: JSON.stringify({
        to: tokenRecord.token,
        priority: "high",
        data: payload,
      }),
    });
  } catch (e) {
    console.error("FCM send error:", e);
  }
}
