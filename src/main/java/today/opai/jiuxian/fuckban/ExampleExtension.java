package today.opai.jiuxian.fuckban;

import today.opai.api.Extension;
import today.opai.api.OpenAPI;
import today.opai.api.annotations.ExtensionInfo;
import today.opai.jiuxian.fuckban.modules.FuckBan;

// Required @ExtensionInfo annotation
@ExtensionInfo(name = "FuckBan",author = "JiuXian",version = "1.0")
public class ExampleExtension extends Extension {
    public static OpenAPI openAPI;

    @Override
    public void initialize(OpenAPI openAPI) {
        ExampleExtension.openAPI = openAPI;
        // Modules
        openAPI.registerFeature(new FuckBan());
    }
}
