package sigmastate.utxo

import sigmastate._
import sigmastate.basics.DLogProtocol.DLogInteractiveProver
import sigmastate.basics.DiffieHellmanTupleInteractiveProver
import sigmastate.helpers.{ContextEnrichingTestProvingInterpreter, ErgoLikeTestProvingInterpreter, SigmaTestingCommons}
import sigmastate.interpreter._
import sigmastate.lang.Terms._

class DistributedSigSpecification extends SigmaTestingCommons {

  implicit lazy val IR: TestingIRContext = new TestingIRContext

  private val ctx = fakeContext

  /**
    * An example test where Alice (A) and Bob (B) are signing an input in a distributed way. A statement which
    * protects the box to spend is "pubkey_Alice && pubkey_Bob". Note that a signature in this case is about
    * a transcript of a Sigma-protocol ((a_Alice, a_Bob), e, (z_Alice, z_Bob)),
    * which is done in non-interactive way (thus "e" is got via a Fiat-Shamir transformation).
    *
    * For that, they are going through following steps:
    *
    * - Bob is generating first protocol message a_Bob and sends it to Alice
    * - Alice forms a hint which contain Bob's commitment "a_Bob", and puts the hint into a hints bag
    * - She proves the statement using the bag, getting the partial protocol transcript
    * (a_Alice, e, z_Alice) as a result and sends "a_Alice" and "z_Alice" to Bob.
    * Please note that "e" is got from both a_Alice and a_Bob.
    *
    * - Bob now also knows a_Alice, so can generate the same "e" as Alice. Thus Bob is generating valid
    * proof ((a_Alice, a_Bob), e, (z_Alice, z_Bob)).
    */
  property("distributed AND (2 out of 2)") {
    val proverA = new ErgoLikeTestProvingInterpreter
    val proverB = new ErgoLikeTestProvingInterpreter
    val verifier: ContextEnrichingTestProvingInterpreter = new ContextEnrichingTestProvingInterpreter

    val pubkeyAlice = proverA.dlogSecrets.head.publicImage
    val pubkeyBob = proverB.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyAlice, "pubkeyB" -> pubkeyBob)
    val prop: Values.Value[SSigmaProp.type] = compile(env, """pubkeyA && pubkeyB""").asSigmaProp

    val (rBob, aBob) = DLogInteractiveProver.firstMessage(pubkeyBob)

    val dlBKnown: Hint = OtherCommitment(pubkeyBob, aBob)
    val bag = HintsBag(Seq(dlBKnown))

    val proofAlice = proverA.prove(prop, ctx, fakeMessage, bag).get

    val bagB = proverB.bagForMultisig(ctx, prop, proofAlice.proof, Seq(pubkeyAlice))
      .addHint(OwnCommitment(pubkeyBob, rBob, aBob))

    val proofBob = proverB.prove(prop, ctx, fakeMessage, bagB).get

    // Proof generated by Alice without getting Bob's part is not correct
    verifier.verify(prop, ctx, proofAlice, fakeMessage).get._1 shouldBe false

    // Compound proof from Bob is correct
    verifier.verify(prop, ctx, proofBob, fakeMessage).get._1 shouldBe true
  }

  /**
    * 3-out-of-3 AND signature
    */
  property("distributed AND (3 out of 3)") {
    val proverA = new ErgoLikeTestProvingInterpreter
    val proverB = new ErgoLikeTestProvingInterpreter
    val proverC = new ErgoLikeTestProvingInterpreter
    val verifier: ContextEnrichingTestProvingInterpreter = new ContextEnrichingTestProvingInterpreter

    val pubkeyAlice = proverA.dlogSecrets.head.publicImage
    val pubkeyBob = proverB.dlogSecrets.head.publicImage
    val pubkeyCarol = proverC.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyAlice, "pubkeyB" -> pubkeyBob, "pubkeyC" -> pubkeyCarol)
    val prop: Values.Value[SSigmaProp.type] = compile(env, """pubkeyA && pubkeyB && pubkeyC""").asSigmaProp

    val (rBob, aBob) = DLogInteractiveProver.firstMessage(pubkeyBob)
    val (rCarol, aCarol) = DLogInteractiveProver.firstMessage(pubkeyBob)

    val dlBKnown: Hint = OtherCommitment(pubkeyBob, aBob)
    val dlCKnown: Hint = OtherCommitment(pubkeyCarol, aCarol)
    val bag = HintsBag(Seq(dlBKnown, dlCKnown))

    val proofAlice = proverA.prove(prop, ctx, fakeMessage, bag).get

    val bagC = proverB.bagForMultisig(ctx, prop, proofAlice.proof, Seq(pubkeyAlice))
      .addHint(OwnCommitment(pubkeyCarol, rCarol, aCarol))
      .addHint(dlBKnown)

    val proofCarol = proverC.prove(prop, ctx, fakeMessage, bagC).get

    val bagB = proverB.bagForMultisig(ctx, prop, proofCarol.proof, Seq(pubkeyAlice, pubkeyCarol))
      .addHint(OwnCommitment(pubkeyBob, rBob, aBob))

    val proofBob = proverB.prove(prop, ctx, fakeMessage, bagB).get

    // Proof generated by Alice without getting Bob's part is not correct
    verifier.verify(prop, ctx, proofAlice, fakeMessage).get._1 shouldBe false

    // Proof generated by Alice without getting Bob's part is not correct
    verifier.verify(prop, ctx, proofCarol, fakeMessage).get._1 shouldBe false

    // Compound proof from Bob is correct
    verifier.verify(prop, ctx, proofBob, fakeMessage).get._1 shouldBe true
  }


  /**
    * An example test where Alice (A), Bob (B) and Carol (C) are signing in a distributed way an input, which is
    * protected by 2-out-of-3 threshold multi-signature.
    *
    * A statement which protects the box to spend is "atLeast(2, Coll(pubkeyA, pubkeyB, pubkeyC))".
    *
    * A scheme for multisigning is following:
    *
    *   - Bob is generating first protocol message (commitment to his randomness) "a" and sends it to Alice
    *   - Alice is generating her proof having Bob's "a" as a hint. She then puts Bob's randomness and fill his
    *     response with zero bits. Thus Alice's signature is not valid. Alice is sending her signature to Bob.
    *   - Bob is extracting Alice's commitment to randomness and response, and also Carol's commitment and response.
    *     He's using his randomness from his first step and completes the (valid) signature.
    */
  property("distributed THRESHOLD - 2 out of 3") {
    val proverA = new ErgoLikeTestProvingInterpreter
    val proverB = new ErgoLikeTestProvingInterpreter
    val proverC = new ErgoLikeTestProvingInterpreter
    val verifier = new ContextEnrichingTestProvingInterpreter

    val pubkeyAlice = proverA.dlogSecrets.head.publicImage
    val pubkeyBob = proverB.dlogSecrets.head.publicImage
    val pubkeyCarol = proverC.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyAlice, "pubkeyB" -> pubkeyBob, "pubkeyC" -> pubkeyCarol)
    val prop = compile(env, """atLeast(2, Coll(pubkeyA, pubkeyB, pubkeyC))""").asSigmaProp

    val (rBob, aBob) = DLogInteractiveProver.firstMessage(pubkeyBob)
    val dlBKnown: Hint = OtherCommitment(pubkeyBob, aBob)

    val bagA = HintsBag(Seq(dlBKnown))
    val proofAlice = proverA.prove(prop, ctx, fakeMessage, bagA).get

    val bagB = proverB.bagForMultisig(ctx, prop, proofAlice.proof, Seq(pubkeyAlice, pubkeyCarol))
      .addHint(OwnCommitment(pubkeyBob, rBob, aBob))

    val proofBob = proverB.prove(prop, ctx, fakeMessage, bagB).get

    // Proof generated by Alice without getting Bob's part is not correct
    verifier.verify(prop, ctx, proofAlice, fakeMessage).get._1 shouldBe false

    // Compound proof from Bob is correct
    verifier.verify(prop, ctx, proofBob, fakeMessage).get._1 shouldBe true
  }

  /**
    * Distributed threshold signature, 3 out of 4 case.
    */
  property("distributed THRESHOLD - 3 out of 4") {
    val proverA = new ErgoLikeTestProvingInterpreter
    val proverB = new ErgoLikeTestProvingInterpreter
    val proverC = new ErgoLikeTestProvingInterpreter
    val proverD = new ErgoLikeTestProvingInterpreter
    val verifier = new ContextEnrichingTestProvingInterpreter

    val pubkeyAlice = proverA.dlogSecrets.head.publicImage
    val pubkeyBob = proverB.dlogSecrets.head.publicImage
    val pubkeyCarol = proverC.dlogSecrets.head.publicImage
    val pubkeyDave = proverD.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyAlice, "pubkeyB" -> pubkeyBob, "pubkeyC" -> pubkeyCarol, "pubkeyD" -> pubkeyDave)
    val prop = compile(env, """atLeast(3, Coll(pubkeyA, pubkeyB, pubkeyC, pubkeyD))""").asSigmaProp

    //Alice, Bob and Carol are signing
    val (rBob, aBob) = DLogInteractiveProver.firstMessage(pubkeyBob)
    val dlBKnown: Hint = OtherCommitment(pubkeyBob, aBob)

    val (rCarol, aCarol) = DLogInteractiveProver.firstMessage(pubkeyCarol)
    val dlCKnown: Hint = OtherCommitment(pubkeyCarol, aCarol)

    val bagA = HintsBag(Seq(dlBKnown, dlCKnown))
    val proofAlice = proverA.prove(prop, ctx, fakeMessage, bagA).get

    val bagC = proverC.bagForMultisig(ctx, prop, proofAlice.proof, Seq(pubkeyAlice, pubkeyDave)) ++
      HintsBag(Seq(dlBKnown, OwnCommitment(pubkeyCarol, rCarol, aCarol)))
    val proofCarol = proverC.prove(prop, ctx, fakeMessage, bagC).get

    val bagB = (proverB.bagForMultisig(ctx, prop, proofAlice.proof, Seq(pubkeyAlice, pubkeyDave)) ++
                proverB.bagForMultisig(ctx, prop, proofCarol.proof, Seq(pubkeyCarol)))
                  .addHint(OwnCommitment(pubkeyBob, rBob, aBob))

    val proofBob = proverB.prove(prop, ctx, fakeMessage, bagB).get

    // Proof generated by Alice without getting Bob's part is not correct
    verifier.verify(prop, ctx, proofAlice, fakeMessage).get._1 shouldBe false

    // Proof generated by Alice without getting Bob's part is not correct
    verifier.verify(prop, ctx, proofCarol, fakeMessage).get._1 shouldBe false

    // Compound proof from Bob is correct
    verifier.verify(prop, ctx, proofBob, fakeMessage).get._1 shouldBe true
  }

  /**
    * Distributed threshold signature, 3 out of 4 case, 1 real and 1 simulated secrets are of DH kind.
    */
  property("distributed THRESHOLD - 3 out of 4 - w. DH") {
    val proverA = new ErgoLikeTestProvingInterpreter
    val proverB = new ErgoLikeTestProvingInterpreter
    val proverC = new ErgoLikeTestProvingInterpreter
    val proverD = new ErgoLikeTestProvingInterpreter
    val verifier = new ContextEnrichingTestProvingInterpreter

    val pubkeyAlice = proverA.dlogSecrets.head.publicImage
    val pubkeyBob = proverB.dhSecrets.head.publicImage
    val pubkeyCarol = proverC.dhSecrets.head.publicImage
    val pubkeyDave = proverD.dhSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyAlice, "pubkeyB" -> pubkeyBob, "pubkeyC" -> pubkeyCarol, "pubkeyD" -> pubkeyDave)
    val prop = compile(env, """atLeast(3, Coll(pubkeyA, pubkeyB, pubkeyC, pubkeyD))""").asSigmaProp

    // Alice, Bob and Carol are signing
    val (rBob, aBob) = DiffieHellmanTupleInteractiveProver.firstMessage(pubkeyBob)
    val dlBKnown: Hint = OtherCommitment(pubkeyBob, aBob)

    val (rCarol, aCarol) = DiffieHellmanTupleInteractiveProver.firstMessage(pubkeyCarol)
    val dlCKnown: Hint = OtherCommitment(pubkeyCarol, aCarol)

    val bagA = HintsBag(Seq(dlBKnown, dlCKnown))
    val proofAlice = proverA.prove(prop, ctx, fakeMessage, bagA).get

    val bagC = proverC.bagForMultisig(ctx, prop, proofAlice.proof, Seq(pubkeyAlice, pubkeyDave)) ++
      HintsBag(Seq(dlBKnown, OwnCommitment(pubkeyCarol, rCarol, aCarol)))
    val proofCarol = proverC.prove(prop, ctx, fakeMessage, bagC).get

    val bagB = (proverB.bagForMultisig(ctx, prop, proofAlice.proof, Seq(pubkeyAlice, pubkeyDave)) ++
                proverB.bagForMultisig(ctx, prop, proofCarol.proof, Seq(pubkeyCarol)))
                  .addHint(OwnCommitment(pubkeyBob, rBob, aBob))

    val proofBob = proverB.prove(prop, ctx, fakeMessage, bagB).get

    // Proof generated by Alice without getting Bob's part is not correct
    verifier.verify(prop, ctx, proofAlice, fakeMessage).get._1 shouldBe false

    // Compound proof from Bob is correct
    verifier.verify(prop, ctx, proofBob, fakeMessage).get._1 shouldBe true
  }

  property("distributed THRESHOLD - 2 out of 5 - DH only") {

    val proverA = new ErgoLikeTestProvingInterpreter
    val proverB = new ErgoLikeTestProvingInterpreter
    val proverC = new ErgoLikeTestProvingInterpreter
    val proverD = new ErgoLikeTestProvingInterpreter
    val proverE = new ErgoLikeTestProvingInterpreter
    val verifier = new ContextEnrichingTestProvingInterpreter

    val pubkeyAlice = proverA.dlogSecrets.head.publicImage
    val pubkeyBob = proverB.dlogSecrets.head.publicImage
    val pubkeyCarol = proverC.dlogSecrets.head.publicImage
    val pubkeyDave = proverD.dlogSecrets.head.publicImage
    val pubkeyEmma = proverE.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyAlice, "pubkeyB" -> pubkeyBob, "pubkeyC" -> pubkeyCarol,
                  "pubkeyD" -> pubkeyDave, "pubkeyE" -> pubkeyEmma)
    val prop = compile(env, """atLeast(2, Coll(pubkeyA, pubkeyB, pubkeyC, pubkeyD, pubkeyE))""").asSigmaProp

    //Alice and Dave are signing
    val (rDave, aDave) = DLogInteractiveProver.firstMessage(pubkeyDave)
    val dlDKnown: Hint = OtherCommitment(pubkeyDave, aDave)

    val bagA = HintsBag(Seq(dlDKnown))
    val proofAlice = proverA.prove(prop, ctx, fakeMessage, bagA).get

    // Proof generated by Alice without interaction w. Dave is not correct
    verifier.verify(prop, ctx, proofAlice, fakeMessage).get._1 shouldBe false

    val bagD = proverD
                .bagForMultisig(ctx, prop, proofAlice.proof, Seq(pubkeyAlice, pubkeyBob, pubkeyCarol, pubkeyEmma))
                .addHint(OwnCommitment(pubkeyDave, rDave, aDave))


    println("proofs: " + bagD.proofs.size)
    println("osis: " + bagD.otherSecretsImages.size)

    val proofDave = proverD.prove(prop, ctx, fakeMessage, bagD).get
    verifier.verify(prop, ctx, proofDave, fakeMessage).get._1 shouldBe true
  }

  property("distributed THRESHOLD - 4 out of 8 - DH only") {

  }

}
