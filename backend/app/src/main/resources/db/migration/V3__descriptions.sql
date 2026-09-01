-- Somewhere to write down what a run or a checkpoint was for.
--
-- A system already had a description; a run and a checkpoint did not, and they are the two things
-- that accumulate. After a dozen forks the list is a column of near-identical names and seeds, and
-- the reason each one exists - the question it was started to answer - lives only in the head of
-- whoever started it. A checkpoint has the same problem in miniature: "tick 250" says when, never
-- why that tick was worth keeping.
--
-- Empty rather than null, so reading code never has to distinguish "no description" from "not set".

ALTER TABLE simulation_run ADD COLUMN description TEXT NOT NULL DEFAULT '';
ALTER TABLE checkpoint ADD COLUMN description TEXT NOT NULL DEFAULT '';
