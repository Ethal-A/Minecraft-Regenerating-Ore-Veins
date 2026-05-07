package net.stargazer.regenerating_ore_veins;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class VeinLocatorSignalEntity extends Entity implements ItemSupplier {
    private static final EntityDataAccessor<ItemStack> DATA_ITEM_STACK = SynchedEntityData.defineId(VeinLocatorSignalEntity.class, EntityDataSerializers.ITEM_STACK);
    private double targetX;
    private double targetY;
    private double targetZ;
    private int life;
    private boolean breakEffect;

    public VeinLocatorSignalEntity(EntityType<? extends VeinLocatorSignalEntity> entityType, Level level) {
        super(entityType, level);
    }

    public VeinLocatorSignalEntity(Level level, double x, double y, double z) {
        this(ModContent.VEIN_LOCATOR_SIGNAL.get(), level);
        this.setPos(x, y, z);
    }

    public void setItem(ItemStack stack) {
        this.getEntityData().set(DATA_ITEM_STACK, stack.isEmpty() ? this.getDefaultItem() : stack.copyWithCount(1));
    }

    @Override
    public ItemStack getItem() {
        return this.getEntityData().get(DATA_ITEM_STACK);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ITEM_STACK, this.getDefaultItem());
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        double size = this.getBoundingBox().getSize() * 4.0D;
        if (Double.isNaN(size)) {
            size = 4.0D;
        }

        size *= 64.0D;
        return distance < size * size;
    }

    public void signalTo(BlockPos pos) {
        double x = pos.getX();
        int y = pos.getY();
        double z = pos.getZ();
        double dx = x - this.getX();
        double dz = z - this.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance > 12.0D) {
            this.targetX = this.getX() + dx / horizontalDistance * 12.0D;
            this.targetZ = this.getZ() + dz / horizontalDistance * 12.0D;
            this.targetY = this.getY() + 8.0D;
        } else {
            this.targetX = x;
            this.targetY = y;
            this.targetZ = z;
        }

        this.life = 0;
    }

    public void setBreakEffect(boolean breakEffect) {
        this.breakEffect = breakEffect;
    }

    @Override
    public void lerpMotion(double x, double y, double z) {
        this.setDeltaMovement(x, y, z);
        if (this.xRotO == 0.0F && this.yRotO == 0.0F) {
            double horizontalDistance = Math.sqrt(x * x + z * z);
            this.setYRot((float) (Mth.atan2(x, z) * 180.0F / Math.PI));
            this.setXRot((float) (Mth.atan2(y, horizontalDistance) * 180.0F / Math.PI));
            this.yRotO = this.getYRot();
            this.xRotO = this.getXRot();
        }
    }

    @Override
    public void tick() {
        super.tick();
        Vec3 movement = this.getDeltaMovement();
        double nextX = this.getX() + movement.x;
        double nextY = this.getY() + movement.y;
        double nextZ = this.getZ() + movement.z;
        double horizontalMovement = movement.horizontalDistance();
        this.setXRot(lerpRotation(this.xRotO, (float) (Mth.atan2(movement.y, horizontalMovement) * 180.0F / Math.PI)));
        this.setYRot(lerpRotation(this.yRotO, (float) (Mth.atan2(movement.x, movement.z) * 180.0F / Math.PI)));
        if (!this.level().isClientSide) {
            double dx = this.targetX - nextX;
            double dz = this.targetZ - nextZ;
            float horizontalDistance = (float) Math.sqrt(dx * dx + dz * dz);
            float angle = (float) Mth.atan2(dz, dx);
            double speed = Mth.lerp(0.0025D, horizontalMovement, horizontalDistance);
            double yMovement = movement.y;
            if (horizontalDistance < 1.0F) {
                speed *= 0.8D;
                yMovement *= 0.8D;
            }

            int yDirection = this.getY() < this.targetY ? 1 : -1;
            movement = new Vec3(Math.cos(angle) * speed, yMovement + (yDirection - yMovement) * 0.015F, Math.sin(angle) * speed);
            this.setDeltaMovement(movement);
        }

        if (this.isInWater()) {
            for (int i = 0; i < 4; i++) {
                this.level().addParticle(ParticleTypes.BUBBLE, nextX - movement.x * 0.25D, nextY - movement.y * 0.25D, nextZ - movement.z * 0.25D, movement.x, movement.y, movement.z);
            }
        } else {
            this.level().addParticle(ParticleTypes.PORTAL, nextX - movement.x * 0.25D + this.random.nextDouble() * 0.6D - 0.3D, nextY - movement.y * 0.25D - 0.5D, nextZ - movement.z * 0.25D + this.random.nextDouble() * 0.6D - 0.3D, movement.x, movement.y, movement.z);
        }

        if (!this.level().isClientSide) {
            this.setPos(nextX, nextY, nextZ);
            this.life++;
            if (this.life > 80) {
                this.playSound(SoundEvents.ENDER_EYE_DEATH, 1.0F, 1.0F);
                this.discard();
                if (this.breakEffect) {
                    this.level().levelEvent(2003, this.blockPosition(), 0);
                }
            }
        } else {
            this.setPosRaw(nextX, nextY, nextZ);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Item", this.getItem().save(this.registryAccess()));
        tag.putBoolean("BreakEffect", this.breakEffect);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Item", CompoundTag.TAG_COMPOUND)) {
            this.setItem(ItemStack.parse(this.registryAccess(), tag.getCompound("Item")).orElse(this.getDefaultItem()));
        } else {
            this.setItem(this.getDefaultItem());
        }

        this.breakEffect = tag.getBoolean("BreakEffect");
    }

    @Override
    public float getLightLevelDependentMagicValue() {
        return 1.0F;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    private ItemStack getDefaultItem() {
        return new ItemStack(ModContent.VEIN_LOCATOR.get());
    }

    private static float lerpRotation(float current, float target) {
        while (target - current < -180.0F) {
            current -= 360.0F;
        }

        while (target - current >= 180.0F) {
            current += 360.0F;
        }

        return Mth.lerp(0.2F, current, target);
    }
}
