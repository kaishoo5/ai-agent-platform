import api from "./api";
import type {ModelSettingsUpdateRequest, RuntimeSettings} from "../types/settings";

export async function getRuntimeSettings(): Promise<RuntimeSettings> {
    const response = await api.get<RuntimeSettings>(
        "/api/settings/runtime",
    );

    return response.data;
}

export async function updateModelSettings(
    request: ModelSettingsUpdateRequest,
): Promise<RuntimeSettings> {
    const response = await api.put<RuntimeSettings>(
        "/api/settings/models",
        request,
    );

    return response.data;
}
