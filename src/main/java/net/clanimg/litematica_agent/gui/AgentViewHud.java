package net.clanimg.litematica_agent.gui;

import net.clanimg.litematica_agent.LitematicaAgentClient;
import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.movement.WorldNavAdapter;
import net.clanimg.litematica_agent.movement.pathing.PathNode;
import net.clanimg.litematica_agent.movement.pathing.PathOptions;
import net.clanimg.litematica_agent.ui.Chat;
import net.clanimg.litematica_agent.view.NavRaycaster;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Top left while the agent works: the world as the agent sees it, in grey (see {@link NavRaycaster}), with the planned
 * path and the block it works on in white. Makes it visible why the agent walks somewhere or gets stuck.
 */
public final class AgentViewHud {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 99;
    private static final int MARGIN = 4;
    private static final double FOV = 70.0;
    /** The picture is redrawn a few times per second; the agent's view changes slowly and this keeps the frame rate. */
    private static final long UPDATE_MILLIS = 125L;
    private static final Identifier TEXTURE = Identifier.of(LitematicaAgentClient.MOD_ID, "agent_view");
    private static final int COLOR_BORDER = 0xFF9CA3AF;

    private final NavRaycaster raycaster = new NavRaycaster(WIDTH, HEIGHT);
    private @Nullable NativeImageBackedTexture texture;
    private long lastUpdate;

    public void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        AgentManager manager = AgentManager.get();
        if (client.options.hudHidden || client.player == null || client.world == null || !manager.config().showAgentView) {
            return;
        }
        AgentManager.WorkView work = manager.workView();
        if (work == null) {
            return;
        }
        if (this.texture == null) {
            this.texture = new NativeImageBackedTexture(() -> "litematica_agent_view", WIDTH, HEIGHT, false);
            client.getTextureManager().registerTexture(TEXTURE, this.texture);
        }
        long now = System.currentTimeMillis();
        if (now - this.lastUpdate >= UPDATE_MILLIS) {
            this.lastUpdate = now;
            this.update(client, work);
        }
        context.fill(MARGIN - 1, MARGIN - 1, MARGIN + WIDTH + 1, MARGIN + HEIGHT + 1, COLOR_BORDER);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, MARGIN, MARGIN, 0.0F, 0.0F, WIDTH, HEIGHT, WIDTH, HEIGHT);
        context.drawTextWithShadow(client.textRenderer, Chat.tr("hud.agent_view"), MARGIN + 3, MARGIN + 3, NavRaycaster.WHITE);
    }

    private void update(MinecraftClient client, AgentManager.WorkView work) {
        ClientPlayerEntity player = client.player;
        Vec3d eye = player.getEyePos();
        this.raycaster.render(new WorldNavAdapter(client.world, PathOptions.NONE),
                new NavRaycaster.Camera(eye.x, eye.y, eye.z, player.getYaw(), player.getPitch(), FOV));

        List<PathNode> path = work.movement().path();
        if (path != null) {
            for (int i = Math.max(0, work.movement().pathIndex()); i < path.size(); i++) {
                PathNode node = path.get(i);
                this.raycaster.drawPoint(node.x() + 0.5, node.y() + 0.05, node.z() + 0.5, NavRaycaster.WHITE);
                if (i + 1 < path.size()) {
                    PathNode next = path.get(i + 1);
                    this.raycaster.drawLine(node.x() + 0.5, node.y() + 0.05, node.z() + 0.5,
                            next.x() + 0.5, next.y() + 0.05, next.z() + 0.5, NavRaycaster.WHITE);
                }
            }
        }
        BlockPos focus = work.focus();
        if (focus != null) {
            this.raycaster.drawBlock(focus.getX(), focus.getY(), focus.getZ(), NavRaycaster.WHITE);
        }

        NativeImage image = this.texture.getImage();
        if (image == null) {
            return;
        }
        int[] pixels = this.raycaster.pixels();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                image.setColorArgb(x, y, pixels[y * WIDTH + x]);
            }
        }
        this.texture.upload();
    }
}
