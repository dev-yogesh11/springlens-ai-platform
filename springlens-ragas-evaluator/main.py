import os
import logging
import uvicorn
from datetime import datetime, timezone
from typing import List, Optional

from dotenv import load_dotenv
load_dotenv()

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from ragas import evaluate, EvaluationDataset, SingleTurnSample
from ragas.metrics import Faithfulness, AnswerRelevancy, ContextPrecision, ContextRecall
from ragas.llms import LangchainLLMWrapper
from ragas.embeddings import LangchainEmbeddingsWrapper
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY")
PORT = int(os.environ.get("PORT", "8081"))
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO")

if not OPENAI_API_KEY:
    raise RuntimeError("OPENAI_API_KEY is not set in .env")

logging.basicConfig(
    level=getattr(logging, LOG_LEVEL.upper(), logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
)
logger = logging.getLogger("springlens-ragas")

class EvaluationPair(BaseModel):
    question: str
    ground_truth: str
    answer: str
    contexts: List[str]

class EvaluationRequest(BaseModel):
    pairs: List[EvaluationPair] = Field(..., min_length=1)
    retrieval_strategy: str

class PerPairScore(BaseModel):
    question: str
    faithfulness: Optional[float]
    answer_relevancy: Optional[float]
    context_precision: Optional[float]
    context_recall: Optional[float]

class AggregateScores(BaseModel):
    faithfulness: Optional[float]
    answer_relevancy: Optional[float]
    context_precision: Optional[float]
    context_recall: Optional[float]

class EvaluationResponse(BaseModel):
    retrieval_strategy: str
    pair_count: int
    scores: AggregateScores
    per_pair_scores: List[PerPairScore]
    evaluated_at: str

app = FastAPI(title="SpringLens RAGAS Evaluator", version="1.0.0")

def safe_score(value) -> Optional[float]:
    try:
        if value is None:
            return None
        f = float(value)
        return None if f != f else round(f, 4)
    except (TypeError, ValueError):
        return None

def average(values: List[Optional[float]]) -> Optional[float]:
    valid = [v for v in values if v is not None]
    if not valid:
        return None
    return round(sum(valid) / len(valid), 4)

@app.get("/health")
async def health():
    return {"status": "ok", "service": "springlens-ragas-evaluator"}

@app.post("/evaluate", response_model=EvaluationResponse)
async def evaluate_rag(request: EvaluationRequest):
    logger.info("Evaluation started | strategy=%s | pairs=%d",
                request.retrieval_strategy, len(request.pairs))
    samples = []
    for pair in request.pairs:
        samples.append(SingleTurnSample(
            user_input=pair.question,
            reference=pair.ground_truth,
            response=pair.answer,
            retrieved_contexts=pair.contexts,
        ))
    dataset = EvaluationDataset(samples=samples)
    try:
        llm = LangchainLLMWrapper(ChatOpenAI(model="gpt-4o-mini", api_key=OPENAI_API_KEY, temperature=0))
        embeddings = LangchainEmbeddingsWrapper(OpenAIEmbeddings(model="text-embedding-3-small", api_key=OPENAI_API_KEY))
        metrics = [
            Faithfulness(llm=llm),
            AnswerRelevancy(llm=llm, embeddings=embeddings),
            ContextPrecision(llm=llm),
            ContextRecall(llm=llm)
        ]
        result = evaluate(dataset=dataset, metrics=metrics)
        logger.info("Evaluation completed")
    except Exception as e:
        logger.error("Evaluation failed: %s", str(e), exc_info=True)
        raise HTTPException(status_code=500, detail=f"RAGAS error: {str(e)}")
    try:
        df = result.to_pandas()
    except Exception as e:
        raise HTTPException(status_code=500, detail="Failed to parse RAGAS result")
    per_pair_scores = []
    f_vals, ar_vals, cp_vals, cr_vals = [], [], [], []
    for i, pair in enumerate(request.pairs):
        row = df.iloc[i]
        f  = safe_score(row.get("faithfulness"))
        ar = safe_score(row.get("answer_relevancy"))
        cp = safe_score(row.get("context_precision"))
        cr = safe_score(row.get("context_recall"))
        f_vals.append(f)
        ar_vals.append(ar)
        cp_vals.append(cp)
        cr_vals.append(cr)
        per_pair_scores.append(PerPairScore(
            question=pair.question,
            faithfulness=f,
            answer_relevancy=ar,
            context_precision=cp,
            context_recall=cr))
    aggregate = AggregateScores(
        faithfulness=average(f_vals),
        answer_relevancy=average(ar_vals),
        context_precision=average(cp_vals),
        context_recall=average(cr_vals))
    logger.info("Scores | faith=%.3f | rel=%.3f | prec=%.3f | rec=%.3f",
                aggregate.faithfulness or 0,
                aggregate.answer_relevancy or 0,
                aggregate.context_precision or 0,
                aggregate.context_recall or 0)
    return EvaluationResponse(
        retrieval_strategy=request.retrieval_strategy,
        pair_count=len(request.pairs),
        scores=aggregate,
        per_pair_scores=per_pair_scores,
        evaluated_at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S"))

if __name__ == "__main__":
    logger.info("Starting on port %d", PORT)
    uvicorn.run("main:app", host="0.0.0.0", port=PORT, reload=False, loop="asyncio")
