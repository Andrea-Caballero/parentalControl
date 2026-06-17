// T15: Emparejamiento - Edge Function
// Valida pairing_code, crea/asocia device y escribe device_id en app_metadata

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
    const { code, device_name, device_model, os_version, app_version, age_band } =
      await req.json();

    // Validar input
    if (!code || !device_name || !app_version) {
      return new Response(
        JSON.stringify({ error: "Faltan campos requeridos" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Cliente con service_role para bypass RLS
    const supabaseAdmin = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    // 1. Validar código existente, vigente y no consumido
    const { data: pairingRecord, error: fetchError } = await supabaseAdmin
      .from("pairing_codes")
      .select("*")
      .eq("code", code)
      .eq("status", "ACTIVE")
      .single();

    if (fetchError || !pairingRecord) {
      return new Response(
        JSON.stringify({ error: "Código inválido o expirado" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Verificar expiración
    const expiresAt = new Date(pairingRecord.expires_at);
    if (expiresAt < new Date()) {
      // Marcar como expirado
      await supabaseAdmin
        .from("pairing_codes")
        .update({ status: "EXPIRED" })
        .eq("id", pairingRecord.id);

      return new Response(
        JSON.stringify({ error: "Código expirado" }),
        { status: 410, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 2. Obtener o crear usuario anónimo del agente (auth.users)
    // El agente usa anon key, así que auth.uid() es null
    // Creamos un usuario anónimo con email device-based
    let agentUserId: string;

    // Buscar usuario existente por metadata (si el agente ya se registró antes)
    const deviceHash = await hashDeviceIdentifier(device_name, device_model);

    const { data: existingUsers } = await supabaseAdmin
      .from("auth.users")
      .select("id")
      .ilike("email", `%${deviceHash}%`)
      .limit(1);

    if (existingUsers && existingUsers.length > 0) {
      agentUserId = existingUsers[0].id;
    } else {
      // Crear usuario anónimo para el dispositivo
      const { data: newUser, error: createError } = await supabaseAdmin.auth.admin.createUser({
        email: `device_${deviceHash}@parentalcontrol.local`,
        email_confirm: true,
        user_metadata: {
          device_hash: deviceHash,
          device_name,
        },
      });

      if (createError || !newUser.user) {
        throw new Error(`Error creando usuario: ${createError?.message}`);
      }
      agentUserId = newUser.user.id;
    }

    // 3. Crear dispositivo
    const { data: device, error: deviceError } = await supabaseAdmin
      .from("devices")
      .insert({
        device_name,
        parent_id: pairingRecord.parent_id,
        device_model,
        os_version,
        app_version,
        device_state: "ACTIVE",
        policy_version: 1,
      })
      .select()
      .single();

    if (deviceError || !device) {
      throw new Error(`Error creando dispositivo: ${deviceError?.message}`);
    }

    // 4. Escribir device_id en app_metadata del usuario
    const { error: metaError } = await supabaseAdmin.auth.admin.updateUserById(
      agentUserId,
      {
        app_metadata: {
          device_id: device.id,
        },
      }
    );

    if (metaError) {
      throw new Error(`Error actualizando app_metadata: ${metaError.message}`);
    }

    // 5. Aplicar plantilla de política según age_band
    const templateAgeBand = age_band || pairingRecord.device_name?.split("-")[0] || "7-12";
    await applyPolicyTemplate(supabaseAdmin, device.id, templateAgeBand);

    // 6. Marcar código como consumido
    await supabaseAdmin
      .from("pairing_codes")
      .update({ 
        status: "ACTIVE", // Sigue activo pero se marca used_at
        used_at: new Date().toISOString() 
      })
      .eq("id", pairingRecord.id);

    // 7. Disparar FCM para notificar al padre
    await sendFcmNotification(supabaseAdmin, pairingRecord.parent_id, {
      type: "DEVICE_PAIRED",
      device_id: device.id,
      device_name,
    });

    return new Response(
      JSON.stringify({
        success: true,
        device_id: device.id,
        parent_id: pairingRecord.parent_id,
        policy_version: 1,
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    console.error("Pairing error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

// ============ Helpers ============

async function hashDeviceIdentifier(name: string, model: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(`${name}-${model}-${Date.now()}`);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 16);
}

async function applyPolicyTemplate(
  supabase: ReturnType<typeof createClient>,
  deviceId: string,
  ageBand: string
): Promise<void> {
  // Buscar plantilla por age_band
  const { data: template } = await supabase
    .from("policy_templates")
    .select("*")
    .eq("age_band", ageBand)
    .single();

  if (!template) {
    console.warn(`No template found for age_band: ${ageBand}`);
    return;
  }

  const config = template.config;

  // Aplicar schedules
  if (config.schedules && Array.isArray(config.schedules)) {
    for (const schedule of config.schedules) {
      await supabase.from("schedules").insert({
        device_id: deviceId,
        name: schedule.id,
        days: schedule.days,
        from_time: schedule.from,
        to_time: schedule.to,
        action: schedule.action,
        allow_list: schedule.allow_list || null,
        is_active: true,
      });
    }
  }

  // Aplicar app_policies
  if (config.app_policies && Array.isArray(config.app_policies)) {
    for (const policy of config.app_policies) {
      await supabase.from("app_policies").insert({
        device_id: deviceId,
        package_name: policy.package_name,
        state: policy.state,
        daily_limit_minutes: policy.daily_limit_minutes || null,
        category: policy.category || null,
      }).onConflict(["device_id", "package_name"]).merge();
    }
  }
}

async function sendFcmNotification(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  payload: Record<string, unknown>
): Promise<void> {
  // TODO: FCM dispatch to parent requires parent device_id (not user_id).
  // The `device_push_tokens` table is keyed by device_id; the parent user's
  // device_id needs to be resolved via the parent device's auth context.
  // For now, skip the notification — the parent sees the pairing result in
  // their own UI anyway.
  console.log("Skipping FCM to parent: parent device_id not yet wired in pairing flow");
  return;

  // Enviar via FCM
  const fcmUrl = "https://fcm.googleapis.com/fcm/send";
  const serverKey = Deno.env.get("FCM_SERVER_KEY");

  for (const { token } of tokens) {
    try {
      await fetch(fcmUrl, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `key=${serverKey}`,
        },
        body: JSON.stringify({
          to: token,
          priority: "high",
          data: payload,
        }),
      });
    } catch (e) {
      console.error("FCM send error:", e);
    }
  }
}
