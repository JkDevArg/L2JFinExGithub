package net.sf.l2j.gameserver.handler.skillhandlers;

import net.sf.l2j.gameserver.handler.IHandler;
import net.sf.l2j.gameserver.model.ShotType;
import net.sf.l2j.gameserver.model.WorldObject;
import net.sf.l2j.gameserver.model.actor.Creature;
import net.sf.l2j.gameserver.network.SystemMessageId;
import net.sf.l2j.gameserver.network.serverpackets.SystemMessage;
import net.sf.l2j.gameserver.skills.Env;
import net.sf.l2j.gameserver.skills.Formulas;
import net.sf.l2j.gameserver.skills.L2Effect;
import net.sf.l2j.gameserver.skills.L2Skill;
import net.sf.l2j.gameserver.skills.basefuncs.Func;
import net.sf.l2j.gameserver.templates.skills.ESkillType;

/**
 * @author Steuf
 */
public class Blow implements IHandler {

	private static final ESkillType[] SKILL_IDS = {
		ESkillType.BLOW
	};

	public static final int FRONT = 50;
	public static final int SIDE = 60;
	public static final int BEHIND = 70;

	@Override
	public void invoke(Object...args) {
		final Creature activeChar = (Creature) args[0];
		final L2Skill skill = (L2Skill) args[1];
		final WorldObject[] targets = (WorldObject[]) args[2];
		if (activeChar.isAlikeDead()) {
			return;
		}

		final boolean ss = activeChar.isChargedShot(ShotType.SOULSHOT);

		for (WorldObject obj : targets) {
			if (!obj.isCreature()) {
				continue;
			}

			final Creature target = ((Creature) obj);
			if (target.isAlikeDead()) {
				continue;
			}

			byte _successChance = SIDE;

			if (activeChar.isBehindTarget()) {
				_successChance = BEHIND;
			} else if (activeChar.isInFrontOfTarget()) {
				_successChance = FRONT;
			}

			// If skill requires Crit or skill requires behind, calculate chance based on DEX, Position and on self BUFF
			boolean success = true;
			if ((skill.getCondition() & L2Skill.COND_BEHIND) != 0) {
				success = (_successChance == BEHIND);
			}
			if ((skill.getCondition() & L2Skill.COND_CRIT) != 0) {
				success = (success && Formulas.calcBlow(activeChar, target, _successChance));
			}

			if (success) {
				// Calculate skill evasion
				boolean skillIsEvaded = Formulas.calcPhysicalSkillEvasion(target, skill);
				if (skillIsEvaded) {
					if (activeChar.isPlayer()) {
						activeChar.getPlayer().sendPacket(SystemMessage.getSystemMessage(SystemMessageId.S1_DODGES_ATTACK).addCharName(target));
					}

					if (target.isPlayer()) {
						target.getPlayer().sendPacket(SystemMessage.getSystemMessage(SystemMessageId.AVOIDED_S1_ATTACK).addCharName(activeChar));
					}

					// no futher calculations needed.
					continue;
				}

				// Calculate skill reflect
				final byte reflect = Formulas.calcSkillReflect(target, skill);
				if (skill.hasEffects()) {
					if (reflect == Formulas.SKILL_REFLECT_SUCCEED) {
						activeChar.stopSkillEffects(skill.getId());
						skill.getEffects(target, activeChar);
						activeChar.sendPacket(SystemMessage.getSystemMessage(SystemMessageId.YOU_FEEL_S1_EFFECT).addSkillName(skill));
					} else {
						final byte shld = Formulas.calcShldUse(activeChar, target, skill);
						target.stopSkillEffects(skill.getId());
						if (Formulas.calcSkillSuccess(activeChar, target, skill, shld, true)) {
							skill.getEffects(activeChar, target, new Env(shld, false, false, false));
							target.sendPacket(SystemMessage.getSystemMessage(SystemMessageId.YOU_FEEL_S1_EFFECT).addSkillName(skill));
						} else {
							activeChar.sendPacket(SystemMessage.getSystemMessage(SystemMessageId.S1_RESISTED_YOUR_S2).addCharName(target).addSkillName(skill));
						}
					}
				}

				final byte shld = Formulas.calcShldUse(activeChar, target, skill);
				final boolean parry = Formulas.calcParry(activeChar, target, skill);

				// Crit rate base crit rate for skill, modified with STR bonus
				boolean crit = false;
				if (Formulas.calcCrit(skill.getBaseCritRate() * 10 * Formulas.STR_BONUS[activeChar.getSTR()])) {
					crit = true;
				}

				double damage = (int) Formulas.calcBlowDamage(activeChar, target, skill, shld, parry, ss);
				if (crit) {
					damage *= 2;

					// Vicious Stance is special after C5, and only for BLOW skills
					L2Effect vicious = activeChar.getFirstEffect(312);
					if (vicious != null && damage > 1) {
						for (Func func : vicious.getStatFuncs()) {
							final Env env = new Env();
							env.setCharacter(activeChar);
							env.setTarget(target);
							env.setSkill(skill);
							env.setValue(damage);

							func.calc(env);
							damage = (int) env.getValue();
						}
					}
				}

				target.reduceCurrentHp(damage, activeChar, skill);

				// vengeance reflected damage
				if ((reflect & Formulas.SKILL_REFLECT_VENGEANCE) != 0) {
					if (target.isPlayer()) {
						target.sendPacket(SystemMessage.getSystemMessage(SystemMessageId.COUNTERED_S1_ATTACK).addCharName(activeChar));
					}

					if (activeChar.isPlayer()) {
						activeChar.sendPacket(SystemMessage.getSystemMessage(SystemMessageId.S1_PERFORMING_COUNTERATTACK).addCharName(target));
					}

					// Formula from Diego post, 700 from rpg tests
					double vegdamage = (700 * target.getPAtk(activeChar) / activeChar.getPDef(target));
					activeChar.reduceCurrentHp(vegdamage, target, skill);
				}

				// Manage cast break of the target (calculating rate, sending message...)
				Formulas.calcCastBreak(target, damage);

				if (activeChar.isPlayer()) {
					activeChar.getPlayer().sendDamageMessage(target, (int) damage, false, true, false, parry);
				}
			} else {
				activeChar.sendPacket(SystemMessage.getSystemMessage(SystemMessageId.ATTACK_FAILED));
			}

			// Possibility of a lethal strike
			Formulas.calcLethalHit(activeChar, target, skill);

			if (skill.hasSelfEffects()) {
				final L2Effect effect = activeChar.getFirstEffect(skill.getId());
				if (effect != null && effect.isSelfEffect()) {
					effect.exit();
				}

				skill.getEffectsSelf(activeChar);
			}
			activeChar.setChargedShot(ShotType.SOULSHOT, skill.isStaticReuse());
		}
	}

	@Override
	public ESkillType[] commands() {
		return SKILL_IDS;
	}
}
