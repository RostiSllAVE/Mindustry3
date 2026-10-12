package fpv.defense;

/** topZ is the full undamaged bound; effective height follows the single pool's HP fraction. */
public record ProjectionSnapshot(int anchorPos,int blockId,int teamId,float hp,float maxHp,
                                 float cooldown,float baseZ,float topZ,long revision) {}
